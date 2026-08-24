package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.counterSpell
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `FW-COUNTER` F1.1/F1.3 (docs/design/countering-spells.md): targeting a spell on the stack (CR 115.1,
 * CR 111.1), the CR 701.5a counter action, and the CR 118.3a "unless its controller pays" clause.
 *
 * `mtg-rules` names no card (ADR-003), so everything here runs on the synthetic counters of
 * `CounterFixtures.kt`. The point the whole file turns on is the verdict split: **countered**
 * (CR 701.5) and **did not resolve** (CR 608.2b) are the same state transition for different reasons and
 * emit different events. `FizzleVerdictAcceptanceSpec` is the other half of that guard and is
 * deliberately untouched by this framework.
 */
class CounteringSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val anySpell = TargetSpec.SpellOnStack(SpellRestriction.Any)

        // ---- CR 115.1 / CR 111.1: enumerating a spell on the stack ---------------------------------

        "CR 115.1/111.1: the stack spec enumerates spells bottom-up and never an ability" {
            val state =
                stackedState(spellEntry(10, fixtureBolt, alice), abilityEntry(), spellEntry(11, fixtureComet, bob))

            legalTargets(state, anySpell, alice, self = null) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(10)), Target.SpellOnStack(ObjectId(11)))
        }

        "CR 113.7a: an ability on the stack carries no card, so no target can name it" {
            legalTargets(stackedState(abilityEntry()), anySpell, alice, self = null).shouldBeEmpty()
        }

        "CR 601.2c: a spell is never a legal target for itself, so cast time and re-validation agree" {
            val state = stackedState(spellEntry(10, fixtureBolt, alice), spellEntry(11, fixtureCounter, bob))

            // The re-validation enumeration, with the counter already on the stack under id 11, names
            // exactly what the gathering enumeration named while the card was still in hand.
            legalTargets(state, anySpell, bob, self = ObjectId(11)) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(10)))
            // Without the exclusion the two would disagree — the counter would offer itself.
            legalTargets(state, anySpell, bob, self = null) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(10)), Target.SpellOnStack(ObjectId(11)))
        }

        // ---- CR 115.1: each SpellRestriction member ------------------------------------------------

        "CR 205.2: OfCardType offers only spells of that type, NotOfCardType only spells without it" {
            val state = restrictionBoard()

            legalTargets(state, spec(SpellRestriction.OfCardType(CardType.INSTANT)), alice, self = null)
                .shouldContainExactly(listOf(Target.SpellOnStack(ObjectId(10)), Target.SpellOnStack(ObjectId(13))))
            legalTargets(state, spec(SpellRestriction.NotOfCardType(CardType.CREATURE)), alice, self = null)
                .shouldContainExactly(
                    listOf(
                        Target.SpellOnStack(ObjectId(10)),
                        Target.SpellOnStack(ObjectId(11)),
                        Target.SpellOnStack(ObjectId(13)),
                    ),
                )
        }

        "CR 205.2: OfAnyCardType offers a spell carrying either named type" {
            val artifactOrEnchantment =
                spec(SpellRestriction.OfAnyCardType(persistentSetOf(CardType.CREATURE, CardType.SORCERY)))

            legalTargets(restrictionBoard(), artifactOrEnchantment, alice, self = null) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(11)), Target.SpellOnStack(ObjectId(12)))
        }

        "CR 202.2: OfColor reads a spell's colour off its printed mana cost" {
            val state = restrictionBoard()

            // Bolt, Comet, and Bear are all {R}; Meditation's {1} makes it colourless (CR 105.4).
            legalTargets(state, spec(SpellRestriction.OfColor(Color.RED)), alice, self = null) shouldContainExactly
                listOf(
                    Target.SpellOnStack(ObjectId(10)),
                    Target.SpellOnStack(ObjectId(11)),
                    Target.SpellOnStack(ObjectId(12)),
                )
            legalTargets(state, spec(SpellRestriction.OfColor(Color.BLUE)), alice, self = null).shouldBeEmpty()
        }

        "ADR-005: a counter with no legal spell on the stack is not enumerated at all" {
            // An empty stack means no legal target, so casting the counter is not offered (CR 601.2c) —
            // the exclusion that keeps a cast from dead-ending mid-pipeline.
            val state =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Counter"), battlefield = listOf("Fixture Island")),
                    bobSetup = SeatSetup(),
                    definitions = counterDefinitions,
                )
            enumeratedCasts(pausedRequestOf(state)).shouldBeEmpty()
        }

        // ---- CR 701.5a: the counter action ----------------------------------------------------------

        "CR 701.5a: a counter removes its target from mid-stack to its owner's graveyard, effect unrun" {
            // Stack, bottom-up: alice's Bolt at bob, bob's Meditation, bob's Counter aimed at the Bolt.
            val resolved =
                resolveTopOfStack(
                    stackedState(
                        spellEntry(10, fixtureBolt, alice, targets = listOf(Target.Player(bob))),
                        spellEntry(11, fixtureMeditation, bob),
                        spellEntry(12, fixtureCounter, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                    ),
                ).pausedState

            // The Bolt left from the *middle* of the stack; the Meditation above it is untouched.
            resolved.sharedZones.stack.map { (it as StackEntry.Spell).obj.id } shouldContainExactly listOf(ObjectId(11))
            // CR 701.5a: none of the countered spell's instructions were performed.
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE
            // CR 701.5a/400.7: its card is in its **owner's** graveyard, reborn under a fresh id.
            resolved.player(alice).graveyard.map { it.card } shouldContainExactly listOf(CardRef("Fixture Bolt"))
            resolved
                .player(alice)
                .graveyard
                .single()
                .id shouldNotBe ObjectId(10)
            // The verdict is "countered", never "fizzled", and it names both halves.
            val countered = resolved.events.filterIsInstance<GameEvent.SpellCountered>().single()
            countered.objectId shouldBe ObjectId(10)
            countered.controller shouldBe alice
            countered.counteredBy shouldBe ObjectId(12)
            resolved.events.filterIsInstance<GameEvent.SpellFizzled>().shouldBeEmpty()
            // The counter itself *resolved* (CR 608.2m); countering is what it did, not what befell it.
            resolved.events.filterIsInstance<GameEvent.SpellResolved>().map { it.objectId } shouldContainExactly
                listOf(ObjectId(12))
        }

        "CR 608.2b: a counter whose target has already left the stack fizzles, and is not 'countered'" {
            // Two counters stacked above one Bolt: the top one counters it, the lower one is then left
            // with no legal target. The graveyard card carries a fresh id (CR 400.7), so the stale
            // target matches nothing anywhere and the fizzle arrives through the existing enumeration.
            val start =
                stackedState(
                    spellEntry(10, fixtureBolt, alice, targets = listOf(Target.Player(bob))),
                    spellEntry(11, fixtureCounter, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                    spellEntry(12, fixtureCounter, alice, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                )
            val afterLower = resolveTopOfStack(resolveTopOfStack(start).pausedState).pausedState

            afterLower.sharedZones.stack.shouldBeEmpty()
            // One counter, one fizzle — never both for the same object, and never the wrong event.
            afterLower.events.filterIsInstance<GameEvent.SpellCountered>().map { it.objectId } shouldContainExactly
                listOf(ObjectId(10))
            afterLower.events.filterIsInstance<GameEvent.SpellFizzled>().map { it.objectId } shouldContainExactly
                listOf(ObjectId(11))
        }

        "CR 702.34e: a countered flashback spell is exiled instead of going to a graveyard" {
            val resolved =
                resolveTopOfStack(
                    stackedState(
                        spellEntry(
                            10,
                            fixtureEcho,
                            alice,
                            castVia = CastingPermission.Flashback(ManaCost.parse("{R}")),
                        ),
                        spellEntry(11, fixtureCounter, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                    ),
                ).pausedState

            resolved.player(alice).graveyard.shouldBeEmpty()
            resolved.sharedZones.exile.map { it.card } shouldContainExactly listOf(CardRef("Fixture Echo"))
            resolved.events.filterIsInstance<GameEvent.SpellCountered>() shouldHaveSize 1
            resolved.events.filterIsInstance<GameEvent.SpellExiledInsteadOfGraveyard>() shouldHaveSize 1
        }

        "CR 608.3: a countered creature spell never enters the battlefield" {
            val resolved =
                resolveTopOfStack(
                    stackedState(
                        spellEntry(10, fixtureBear, alice),
                        spellEntry(11, fixtureCounter, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                    ),
                ).pausedState

            resolved.sharedZones.battlefield.shouldBeEmpty()
            resolved.events.filterIsInstance<GameEvent.PermanentEntered>().shouldBeEmpty()
            resolved.player(alice).graveyard.map { it.card } shouldContainExactly listOf(CardRef("Fixture Bear"))
        }

        "CR 701.5a: countering is not an un-cast — a cast trigger already on the stack still resolves" {
            // The trigger fired at CR 601.2i and sits above the spell it watched; countering the spell
            // leaves it alone, because a countered spell was still cast.
            val resolved =
                resolveTopOfStack(
                    stackedState(
                        spellEntry(10, fixtureBolt, alice, targets = listOf(Target.Player(bob))),
                        castWatcherTrigger(),
                        spellEntry(12, fixtureCounter, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
                    ),
                ).pausedState

            resolved.sharedZones.stack shouldHaveSize 1
            resolved.sharedZones.stack
                .single()
                .shouldBeInstanceOf<StackEntry.Ability>()
                .trigger.sourceCard shouldBe CardRef("Fixture Cast Watcher")
        }

        "CR 701.5a: the counter primitive fails loudly on a target that has already left the stack" {
            // Unreachable through resolution — CR 608.2b fizzles such a counter first — so the primitive
            // treats it as an engine defect rather than a silent no-op.
            val state = stackedState(spellEntry(10, fixtureBolt, alice))
            shouldThrowAny { counterSpell(state, Target.SpellOnStack(ObjectId(77)), ObjectId(10)) }
            shouldThrowAny { counterSpell(state, Target.Player(alice), ObjectId(10)) }
        }

        // ---- CR 118.3a: "unless its controller pays" ------------------------------------------------

        "CR 118.3a: the decider is the targeted spell's controller, not the resolving counter's" {
            val request = spikePause().pending<DecisionRequest.ChooseCounterPayment>()

            // Alice cast the Bolt; bob cast the Spike. Alice is the one asked to pay.
            request.seat shouldBe alice
            request.card shouldBe CardRef("Fixture Bolt")
            request.cost shouldBe ManaCost.parse("{1}")
            request.options.first() shouldBe DecisionRequest.ChooseCounterPayment.Option.Decline
        }

        "CR 118.3a: declining counters the spell, and the counter still resolves as a resolved spell" {
            val paused = spikePause()
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            val resolved = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState

            resolved.pendingCounterPayment shouldBe null
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE
            resolved.events.filterIsInstance<GameEvent.SpellCountered>().map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Bolt"))
            resolved.events.filterIsInstance<GameEvent.SpellResolved>().map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Spike"))
        }

        "CR 118.3a: paying in full saves the spell and the counter resolves having done nothing" {
            val paused = spikePause()
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            // Index 0 declines; alice's one untapped source affords exactly one plan after it.
            request.options shouldHaveSize 2
            val resolved = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, 1)).pausedState

            resolved.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
            resolved.sharedZones.stack.map { (it as StackEntry.Spell).obj.card } shouldContainExactly
                listOf(CardRef("Fixture Bolt"))
            // CR 601.2h: the payment really happened — alice's land is tapped for it.
            resolved.sharedZones.battlefield.count { it.owner == alice && it.tapped } shouldBe 1
            resolved.events.filterIsInstance<GameEvent.SpellResolved>().map { it.card } shouldContainExactly
                listOf(CardRef("Fixture Spike"))
        }

        "CR 118.3a: a decider who cannot pay gets a decline-only request, and the spell is countered" {
            // No untapped source at all, so no plan is affordable — but the request is still surfaced
            // (the ChoosePaymentPlan precedent: a uniform decision sequence keeps replay logs canonical)
            // with exactly the one legal answer, rather than offering a "yes" that dead-ends.
            val paused = spikePause(aliceSources = emptyList())
            val request = paused.pending<DecisionRequest.ChooseCounterPayment>()
            request.options shouldContainExactly listOf(DecisionRequest.ChooseCounterPayment.Option.Decline)

            val resolved = engine.advance(paused.pausedState, Decision.SingleSelect(request.id, DECLINE)).pausedState
            resolved.events.filterIsInstance<GameEvent.SpellCountered>() shouldHaveSize 1
        }

        "CR 608.2b before CR 118.3a: a counter whose target has gone fizzles and nobody is asked to pay" {
            // The Pierce's only target left the stack, so the re-check runs first and the unless-pay
            // pause is never entered. This ordering is the thing Spell Pierce's shape exists to pin.
            val resolved =
                resolveTopOfStack(
                    stackedState(
                        spellEntry(11, fixturePierce, bob, targets = listOf(Target.SpellOnStack(ObjectId(77)))),
                    ),
                ).pausedState

            resolved.pendingCounterPayment shouldBe null
            resolved.events.filterIsInstance<GameEvent.SpellFizzled>() shouldHaveSize 1
            resolved.events.filterIsInstance<GameEvent.SpellCountered>().shouldBeEmpty()
        }

        "CR 115.1: a noncreature-restricted counter cannot target a creature spell at all" {
            val state = stackedState(spellEntry(10, fixtureBear, alice))
            legalTargets(state, fixturePierce.targetSpec, bob, self = null).shouldBeEmpty()
            legalTargets(state, fixtureNegate.targetSpec, bob, self = null).shouldBeEmpty()
            // …while the unrestricted counter and an artifact-or-enchantment one differ on the same board.
            legalTargets(state, fixtureCounter.targetSpec, bob, self = null) shouldHaveSize 1
            legalTargets(state, fixtureAnnul.targetSpec, bob, self = null).shouldBeEmpty()
        }
    })

/** The decline index of an unless-pay request (CR 118.3a): always 0. */
private const val DECLINE: Int = 0

/** A spell-on-stack spec over [restriction]. */
private fun spec(restriction: SpellRestriction): TargetSpec = TargetSpec.SpellOnStack(restriction)

/**
 * A stack holding one red instant (10), one red sorcery (11), one red creature spell (12), and one
 * colourless instant (13) — enough to separate every [SpellRestriction] member from every other.
 */
private fun restrictionBoard(): GameState =
    stackedState(
        spellEntry(10, fixtureBolt, alice),
        spellEntry(11, fixtureComet, bob),
        spellEntry(12, fixtureBear, alice),
        spellEntry(13, fixtureMeditation, bob),
    )

/**
 * A paused unless-pay decision (CR 118.3a): alice's Fixture Bolt on the stack under bob's Fixture Spike,
 * with [aliceSources] untapped on alice's side to pay from.
 */
private fun spikePause(aliceSources: List<String> = listOf("Fixture Island")): AdvanceResult =
    resolveTopOfStack(
        stackedState(
            spellEntry(10, fixtureBolt, alice, targets = listOf(Target.Player(bob))),
            spellEntry(11, fixtureSpike, bob, targets = listOf(Target.SpellOnStack(ObjectId(10)))),
            aliceBattlefield = aliceSources,
        ),
    )

/** One spell on the stack, by id, definition, controller, chosen targets, and cast permission. */
private fun spellEntry(
    id: Long,
    definition: SpellDefinition,
    controller: PlayerId,
    targets: List<Target> = emptyList(),
    castVia: CastingPermission? = null,
): StackEntry.Spell =
    StackEntry.Spell(
        obj = GameObject(ObjectId(id), CardRef(definition.characteristics.name), controller),
        controller = controller,
        targets = targets.toPersistentList(),
        definition = definition,
        castVia = castVia,
    )

/** A fired triggered ability on the stack — the thing a stack target must never be able to name. */
private fun abilityEntry(): StackEntry.Ability = triggerEntry(CardRef("Fixture Watcher"))

/** A cast trigger (CR 601.2i) already on the stack, standing in for Murmuring Mystic's. */
private fun castWatcherTrigger(): StackEntry.Ability = triggerEntry(CardRef("Fixture Cast Watcher"))

private fun triggerEntry(source: CardRef): StackEntry.Ability =
    StackEntry.Ability(
        PendingTrigger(
            sourceId = ObjectId(900),
            sourceCard = source,
            controller = alice,
            ability =
                TriggeredAbility(
                    condition = TriggerCondition.SpellCast(),
                    effect = ResolutionEffect { state, _ -> state },
                ),
        ),
    )

/**
 * A handcrafted state with [entries] already on the stack and no player holding priority — the shape
 * `resolveTopOfStack` consumes directly (CR 608.1). [aliceBattlefield] gives alice untapped sources,
 * which the CR 118.3a payment needs. Battlefield ids start at 0; stack ids are the caller's, from 10.
 */
private fun stackedState(
    vararg entries: StackEntry,
    aliceBattlefield: List<String> = emptyList(),
): GameState {
    val field =
        aliceBattlefield.mapIndexed { index, name -> GameObject(ObjectId(index.toLong()), CardRef(name), alice) }
    return GameState(
        players = persistentMapOf(alice to seatState(), bob to seatState()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = field.toPersistentList(),
                stack = entries.toList().toPersistentList(),
                exile = persistentListOf(),
            ),
        nextObjectId = 1000,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = counterDefinitions.toPersistentMap(),
    )
}

/** A seat with nothing but a life total; no player holds priority while a resolution runs (CR 608.1). */
private fun seatState(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = PriorityStatus.NONE,
    )
