package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.PermanentSelection
import dev.mtgplay.core.definition.PermanentSelectionAction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import dev.mtgplay.rules.effect.skipNextUntapStep
import dev.mtgplay.rules.effect.tapPermanent
import dev.mtgplay.rules.effect.untapPermanent
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.untapStepTurnBasedActions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-TAPUNTAP`: tapping and untapping as **resolution effects** (CR 701.21a, CR 701.21b), the
 * CR 502.2 "doesn't untap during its controller's next untap step" marker, the
 * [AbilityCost.ReturnPermanentYouControl] activation cost with its CR 602.5b once-each-turn
 * restriction, the untargeted CR 609.4 permanent selection, and [ManaAmount.FixedMultiset] production.
 *
 * Fixtures mirror the shapes of Harrier Strix, Sleep of the Dead, Quirion Ranger, Snap and Azorius
 * Chancery without naming them (the `mtg-rules`-names-no-card rule holds).
 */
class TapUntapSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- CR 701.21a / CR 701.21b: the effect primitives ---------------------------------------

        "CR 701.21a: tapping an untapped permanent taps it and narrates the change once" {
            val state = tapState(aliceBattlefield = listOf(BEAR))
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val tapped = tapPermanent(state, bear)
            tapped.sharedZones.battlefield
                .single()
                .tapped shouldBe true
            tapped.events.count { it is GameEvent.ObjectTapped } shouldBe 1
        }

        "CR 701.21a: tapping an already-tapped permanent leaves it tapped and narrates nothing" {
            // The rule is explicit that a permanent already in the requested status is unaffected —
            // which is exactly where an effect differs from a `{T}` cost (CR 602.2a), whose whole job
            // is to refuse a tapped source. A narrated tap that did not happen would be a lie in the
            // replay log (ADR-006).
            val state = tapState(aliceBattlefield = listOf(BEAR), tapAliceBattlefield = true)
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val tapped = tapPermanent(state, bear)
            tapped.sharedZones.battlefield
                .single()
                .tapped shouldBe true
            tapped.events.none { it is GameEvent.ObjectTapped } shouldBe true
        }

        "CR 701.21b: untapping an already-untapped permanent is a no-op and narrates nothing" {
            val state = tapState(aliceBattlefield = listOf(BEAR))
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val untapped = untapPermanent(state, bear)
            untapped.sharedZones.battlefield
                .single()
                .tapped shouldBe false
            untapped.events.none { it is GameEvent.ObjectUntapped } shouldBe true
        }

        // Two packets wrote this primitive independently and disagreed here: `FW-NINJUTSU`'s copy
        // required a battlefield object, this one absorbed a bad id. The loud contract won on merge.
        // Only a permanent has a tapped status (CR 110.5b), so an id that names no permanent is not a
        // rules case the engine can answer — it means an effect or a selection kept a choice past the
        // point it was legal, which is the ADR-005 failure a silent no-op would hide.
        "CR 110.5b: tapping an object that is not on the battlefield is an engine defect, not a no-op" {
            val state = tapState(aliceBattlefield = listOf(BEAR))
            shouldThrow<IllegalArgumentException> { tapPermanent(state, ObjectId(9999)) }
            shouldThrow<IllegalArgumentException> { untapPermanent(state, ObjectId(9999)) }
            shouldThrow<IllegalArgumentException> { skipNextUntapStep(state, ObjectId(9999)) }
        }

        // ---- CR 502.2: "doesn't untap during its controller's next untap step" ---------------------

        "CR 502.2: a marked permanent does not untap in its controller's untap step, and spends the marker" {
            val state = tapState(aliceBattlefield = listOf(BEAR), tapAliceBattlefield = true)
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val slept = skipNextUntapStep(state, bear)
            val stepped = untapStepTurnBasedActions(slept)
            val after = stepped.sharedZones.battlefield.single()
            after.tapped shouldBe true
            // The marker is spent by the step it named, so the *following* untap step frees it.
            after.skipsNextUntapStep shouldBe false
            stepped.events.none { it is GameEvent.ObjectUntapped } shouldBe true
            untapStepTurnBasedActions(stepped)
                .sharedZones.battlefield
                .single()
                .tapped shouldBe false
        }

        "CR 502.2: the marker is spent by an untap step even when the permanent was already untapped" {
            // "Its controller's next untap step" names a *step*, not an event: an untapped permanent's
            // rider is used up by the very next untap step doing nothing, and does not lie in wait.
            val state = tapState(aliceBattlefield = listOf(BEAR))
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val stepped = untapStepTurnBasedActions(skipNextUntapStep(state, bear))
            stepped.sharedZones.battlefield
                .single()
                .skipsNextUntapStep shouldBe false
        }

        "CR 502.2: the marker is spent only in its own controller's untap step" {
            // The untap step untaps the *active* player's permanents (CR 502.2), so an opponent's turn
            // must leave both the tapped status and the marker alone.
            val state = tapState(aliceBattlefield = listOf(BEAR), tapAliceBattlefield = true)
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val slept = skipNextUntapStep(state, bear)
            val bobsTurn = slept.copy(turn = slept.turn.copy(activePlayer = bob))
            val after = untapStepTurnBasedActions(bobsTurn).sharedZones.battlefield.single()
            after.tapped shouldBe true
            after.skipsNextUntapStep shouldBe true
        }

        // ---- CR 603.6c: a bounce fires the leaves-the-battlefield trigger --------------------------

        "CR 603.6c: returning a permanent to its owner's hand fires its leaves-the-battlefield trigger" {
            // The gap this closes was live and silent: `returnPermanentToOwnersHand` was the one route
            // out of the battlefield that announced no departure, so bouncing a Journey-to-Nowhere-shaped
            // permanent left the card it was holding exiled forever, with nothing to notice.
            val state = tapState(aliceBattlefield = listOf(WATCHER))
            val watcher =
                state.sharedZones.battlefield
                    .single()
                    .id
            val bounced = returnPermanentToOwnersHand(state, watcher)
            bounced.pendingTriggers.map { it.sourceCard } shouldContainExactly listOf(WATCHER)
            // CR 603.6b is *not* fired: nothing went to a graveyard.
            bounced.players
                .getValue(alice)
                .graveyard
                .shouldBeEmptyZone()
        }

        // ---- CR 605.1a: the mixed production ------------------------------------------------------

        "CR 605.1a: a mixed production is one alternative adding both its mana, not a choice between them" {
            val state = tapState(aliceBattlefield = listOf(KAROO))
            val profile = manaSourceClasses(state, alice).single().key.profile
            // One alternative, not two: the Karoo adds {W} *and* {U}, it does not offer a pick.
            profile.map { it.produced } shouldContainExactly
                listOf(listOf(ManaType.WHITE, ManaType.BLUE))
        }

        // ---- CR 602.1 / CR 701.4a: the return-a-permanent activation cost --------------------------

        "CR 602.1: a return-a-permanent cost enumerates only the permanents matching its filter" {
            val state = tapState(aliceBattlefield = listOf(RANGER, GROVE, BEAR), tapAliceBattlefield = true)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, activateDecision(window, RANGER))
            // CR 601.2c first: the untap target, then the cost's chosen object.
            current = engine.advance(current.pausedState, firstTargetDecision(current.pending()))
            val returnRequest = current.pending<DecisionRequest.ChooseAbilityReturn>()
            // The Grove alone: the Ranger is no Grove and the Bear is no land.
            returnRequest.options.map { it.card } shouldContainExactly listOf(GROVE)
            returnRequest.count shouldBe 1
        }

        "CR 701.4a: paying the return cost puts the chosen permanent in its owner's hand, then the ability untaps" {
            val state = tapState(aliceBattlefield = listOf(RANGER, GROVE, BEAR), tapAliceBattlefield = true)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, activateDecision(window, RANGER))
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            val bearIndex = targets.options.indexOfFirst { it == Target.Permanent(objectOf(state, BEAR)) }
            current = engine.advance(current.pausedState, Decision.SingleSelect(targets.id, bearIndex))
            val returnRequest = current.pending<DecisionRequest.ChooseAbilityReturn>()
            current = engine.advance(current.pausedState, Decision.MultiSelect(returnRequest.id, listOf(0)))
            // The cost is paid inside the activation's own transition: the Grove is already in hand.
            val paid = current.pausedState
            paid.sharedZones.battlefield.none { it.card == GROVE } shouldBe true
            paid.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(GROVE)
            // Resolve the ability: the Bear untaps.
            current = engine.advance(paid, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current.pausedState.sharedZones.battlefield
                .single { it.card == BEAR }
                .tapped shouldBe false
        }

        "CR 602.5b: an 'activate only once each turn' ability is not enumerated again the same turn" {
            val state = tapState(aliceBattlefield = listOf(RANGER, GROVE, GROVE, BEAR), tapAliceBattlefield = true)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, activateDecision(window, RANGER))
            current = engine.advance(current.pausedState, firstTargetDecision(current.pending()))
            val returnRequest = current.pending<DecisionRequest.ChooseAbilityReturn>()
            current = engine.advance(current.pausedState, Decision.MultiSelect(returnRequest.id, listOf(0)))
            // A second Grove is still on the battlefield, so the cost is payable — but the CR 602.5b
            // restriction is per *object* and this object has spent it.
            val after = current.pending<DecisionRequest.ChooseAction>()
            after.options.none { it is PriorityOption.ActivateAbility && it.card == RANGER } shouldBe true
            current.pausedState.sharedZones.battlefield
                .single { it.card == RANGER }
                .activatedAbilitiesActivatedThisTurn shouldBe persistentSetOf(0)
        }

        // ---- CR 609.4: the untargeted permanent selection ------------------------------------------

        "CR 609.4: an untargeted selection over lands offers an opponent's hexproof land" {
            // The whole point of the clause being a selection rather than a targeting line: CR 702.11a
            // speaks of targeting alone, so a hexproof permanent is a perfectly legal *choice*.
            val state =
                tapState(
                    aliceBattlefield = listOf(BEAR),
                    aliceHand = listOf(SNAPPER),
                    bobBattlefield = listOf(HEXPROOF_LAND),
                    tapAliceBattlefield = true,
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), SNAPPER.name))
            current = engine.advance(current.pausedState, firstTargetDecision(current.pending()))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // Both players pass; the spell resolves and pauses on the untargeted land selection.
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            val selection = current.pending<DecisionRequest.ChoosePermanentsToAffect>()
            selection.options.map { it.card } shouldContainExactly listOf(HEXPROOF_LAND)
            // "Up to two", clamped to the one land the board actually offers.
            selection.minimumCount shouldBe 0
            selection.maximumCount shouldBe 1
        }

        "CR 609.4: an 'up to N' selection may legally choose none, and the resolution completes" {
            val state =
                tapState(
                    aliceBattlefield = listOf(BEAR),
                    aliceHand = listOf(SNAPPER),
                    bobBattlefield = listOf(HEXPROOF_LAND),
                    tapAliceBattlefield = true,
                )
            var current = engine.advance(state, castDecision(pausedRequestOf(state), SNAPPER.name))
            current = engine.advance(current.pausedState, firstTargetDecision(current.pending()))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            val selection = current.pending<DecisionRequest.ChoosePermanentsToAffect>()
            current = engine.advance(current.pausedState, Decision.MultiSelect(selection.id, emptyList()))
            val resolved = current.pausedState
            // The spell finished: its targeted half bounced the Bear, and no land was untapped.
            resolved.sharedZones.stack.isEmpty() shouldBe true
            resolved.sharedZones.battlefield.none { it.card == BEAR } shouldBe true
            resolved.sharedZones.battlefield
                .single { it.card == HEXPROOF_LAND }
                .tapped shouldBe true
        }
    })

// ---- fixtures ---------------------------------------------------------------------------------

private val BEAR = CardRef("Fixture Untap Bear")
private val GROVE = CardRef("Fixture Grove")
private val RANGER = CardRef("Fixture Ranger")
private val KAROO = CardRef("Fixture Karoo")
private val SNAPPER = CardRef("Fixture Snapper")
private val HEXPROOF_LAND = CardRef("Fixture Warded Land")
private val WATCHER = CardRef("Fixture Departure Watcher")

/** The land subtype the Ranger's return cost names (CR 205.3) — Quirion Ranger's "Forest". */
private val GROVE_TYPE = Subtype("Grove")

/** A vanilla 2/2 the untap and bounce effects act on. */
private val bearDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = BEAR.name,
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Bear")),
                powerToughness = PrintedPowerToughness(2, 2),
            )
    }

/** A Grove-typed land: the object the Ranger's cost returns, and an ordinary `{G}` source. */
private val groveDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = GROVE.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(GROVE_TYPE),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
    }

/** A land whose printed hexproof (CR 702.11) must **not** narrow an untargeted selection. */
private val wardedLandDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = HEXPROOF_LAND.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
                keywords = persistentSetOf(Keyword.HEXPROOF),
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.BLUE)))
    }

/** Quirion Ranger's shape: a bare return cost, a targeted untap, and CR 602.5b once each turn. */
private val rangerDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = RANGER.name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Elf")),
                powerToughness = PrintedPowerToughness(1, 1),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.ReturnPermanentYouControl(
                                PermanentFilter(subtype = GROVE_TYPE, controlledByYou = true),
                            ),
                        ),
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            untapPermanent(state, (context.targets.single() as Target.Permanent).id)
                        },
                    oncePerTurn = true,
                ),
            )
    }

/** Azorius Chancery's shape: enters tapped, a mixed `{W}{U}` production, and an untargeted bounce. */
private val karooDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = KAROO.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val entersTapped = EntersTapped.Always
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.WHITE, ManaType.BLUE),
                    amount = ManaAmount.FixedMultiset(persistentListOf(ManaType.WHITE, ManaType.BLUE)),
                ),
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                    permanentSelection =
                        PermanentSelection(
                            filter = PermanentFilter(controlledByYou = true, cardType = CardType.LAND),
                            minimum = 1,
                            maximum = 1,
                            action = PermanentSelectionAction.RETURN_TO_OWNERS_HAND,
                        ),
                ),
            )
    }

/** Snap's shape: a targeted bounce plus an untargeted "untap up to two lands" (any controller). */
private val snapperDefinition: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = SNAPPER.name,
                manaCost = ManaCost.parse("{0}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                returnPermanentToOwnersHand(state, (context.targets.single() as Target.Permanent).id)
            }
        override val permanentSelection =
            PermanentSelection(
                filter = PermanentFilter(controlledByYou = false, cardType = CardType.LAND),
                minimum = 0,
                maximum = 2,
                action = PermanentSelectionAction.UNTAP,
            )
    }

/** A permanent whose only ability is a CR 603.6c leaves-the-battlefield trigger. */
private val watcherDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = WATCHER.name,
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.LeftBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, WATCHER_LIFEGAIN)
                        },
                ),
            )
    }

private val tapUntapRegistry: Map<CardRef, CardDefinition> =
    listOf(
        bearDefinition,
        groveDefinition,
        wardedLandDefinition,
        rangerDefinition,
        karooDefinition,
        snapperDefinition,
        watcherDefinition,
    ).associateBy { CardRef(it.characteristics.name) }

/** The battlefield id of the (single) object with printed identity [card]. */
private fun objectOf(
    state: GameState,
    card: CardRef,
): ObjectId =
    state.sharedZones.battlefield
        .single { it.card == card }
        .id

/** The life the departure watcher's leaves-the-battlefield trigger gains (CR 119.3). */
private const val WATCHER_LIFEGAIN: Int = 1

/** Selects the first enumerated target of a single-target request (CR 601.2c). */
private fun firstTargetDecision(request: DecisionRequest.ChooseTargets): Decision.SingleSelect =
    Decision.SingleSelect(request.id, 0)

/** Selects the [PriorityOption.ActivateAbility] of [card] from a priority window (CR 602.1). */
private fun activateDecision(
    request: DecisionRequest.ChooseAction,
    card: CardRef,
): Decision.SingleSelect {
    val index = request.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == card }
    check(index >= 0) { "no ActivateAbility option for ${card.name} in ${request.options}" }
    return Decision.SingleSelect(request.id, index)
}

/** Asserts a zone holds nothing. */
private fun List<GameObject>.shouldBeEmptyZone() {
    this.map { it.card } shouldContainExactly emptyList()
}

/**
 * A handcrafted two-player state over [tapUntapRegistry], with Alice mid-priority-window (CR 117.1) so
 * the engine re-derives the pending request from the state alone (ADR-004). Alice's permanents may be
 * created tapped, which is the starting position most of these rules are about.
 */
private fun tapState(
    aliceBattlefield: List<CardRef> = emptyList(),
    aliceHand: List<CardRef> = emptyList(),
    bobBattlefield: List<CardRef> = emptyList(),
    tapAliceBattlefield: Boolean = false,
): GameState {
    var nextId = 0L

    fun objects(
        cards: List<CardRef>,
        owner: PlayerId,
        tapped: Boolean = false,
    ) = cards
        .map { card ->
            GameObject(ObjectId(nextId), card, owner, tapped = tapped, summoningSick = false)
                .also { nextId += 1 }
        }.toPersistentList()

    val aliceField = objects(aliceBattlefield, alice, tapped = tapAliceBattlefield)
    val bobField = objects(bobBattlefield, bob, tapped = true)
    val hand = objects(aliceHand, alice)
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = hand,
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones((aliceField + bobField).toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = tapUntapRegistry.toPersistentMap(),
    )
}
