package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.EachOpponentDiscards
import dev.mtgplay.core.definition.HandRevealChoice
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.definition.RevealedCardRestriction
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
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
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
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.exileLinkedToSource
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.effect.flickerPermanent
import dev.mtgplay.rules.effect.flickerPermanents
import dev.mtgplay.rules.effect.returnExiledToBattlefield
import dev.mtgplay.rules.effect.returnExiledToOwnersHand
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.announceBattlefieldDeparture
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The exile-and-return frameworks at the rules level, with fixture cards only (the
 * `mtg-rules`-names-no-card rule holds): `FW-BLINK`'s CR 400.7 exile-and-return and CR 702.88 rebound,
 * `FW-TRIGLTB`'s CR 603.6c leaves-the-battlefield trigger, `FW-LINKEDEXILE`'s CR 607.2 linked record,
 * and the two hand clauses — the CR 701.16a reveal-and-choose whose chooser is the **controller**, and
 * the CR 701.7a each-opponent discard whose chooser is **not**.
 *
 * The claims pinned here are the ones a plausible-looking wrong implementation would get wrong silently:
 * that a flicker returns a **new object** whose enters-the-battlefield trigger fires again, that an exile
 * fires CR 603.6c but not CR 603.6b, that two linked sources never read each other's exiled card, and
 * that rebound applies on resolution from a hand and on nothing else.
 */
class ExileAndReturnSpec :
    StringSpec({

        "CR 400.7 / CR 603.6a: a flicker returns a new object and re-fires its enters trigger" {
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(GREETER)))
            val before = state.onePermanent(GREETER)

            val flickered = flickerPermanent(state, before.id)

            // Three objects, one resolution: the permanent, its exile self, and the returning permanent.
            val reborn = flickered.sharedZones.battlefield.single()
            reborn.id shouldNotBe before.id
            reborn.card shouldBe CardRef(GREETER)
            flickered.sharedZones.exile.shouldBeEmpty()
            // The headline claim: coming back is a real CR 603.6a entry, so the ability fires again.
            val fired = flickered.pendingTriggers.single()
            fired.ability.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            fired.sourceId shouldBe reborn.id
            fired.subject shouldBe reborn.id
        }

        "CR 122.2 / CR 400.7: a flickered permanent comes back clean, untapped, and summoning sick" {
            val base = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(OX)))
            val before = base.onePermanent(OX)
            val dressed =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                persistentListOf(
                                    before.copy(
                                        tapped = true,
                                        damageMarked = MARKED_DAMAGE,
                                        summoningSick = false,
                                        counters = persistentMapOf(Counter.PowerToughness(1, 1) to COUNTERS),
                                    ),
                                ),
                        ),
                )

            val reborn = flickerPermanent(dressed, before.id).sharedZones.battlefield.single()

            // CR 122.2: the counters are not removed, they cease to exist — nothing is carried across.
            reborn.counters.keys.shouldBeEmpty()
            reborn.damageMarked shouldBe 0
            reborn.tapped shouldBe false
            reborn.summoningSick shouldBe true
        }

        "CR 400.7: a multi-permanent flicker exiles *both* before returning either" {
            // The printed "then" in "exile two target …, then return those cards". The discriminator is
            // the exile zone mid-sequence, which a fold of the one-permanent flicker would never fill:
            // both departures are matched against a board that still holds the other permanent, so both
            // leaves-the-battlefield triggers see two permanents rather than one.
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(WARDEN, WARDEN)))
            val before = state.allPermanents(WARDEN).map { it.id }

            val flickered = flickerPermanents(state, before)

            // Both departures fired, and the exile zone is empty again — everything came back.
            flickered.pendingTriggers
                .filter { it.ability.condition == TriggerCondition.LeftBattlefieldSelf }
                .shouldHaveSize(2)
            flickered.sharedZones.exile.shouldBeEmpty()
            flickered.sharedZones.battlefield shouldHaveSize 2
            // CR 400.7: two new objects, neither of which is either original.
            flickered.sharedZones.battlefield.none { it.id in before } shouldBe true
        }

        "CR 400.7: the returns happen in the order the targets were named" {
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(GREETER, OX)))
            val greeter = state.onePermanent(GREETER).id
            val ox = state.onePermanent(OX).id

            // Named ox-first, so ox returns first and the battlefield order follows the choice.
            val flickered = flickerPermanents(state, listOf(ox, greeter))

            flickered.sharedZones.battlefield.map { it.card } shouldContainExactly
                listOf(CardRef(OX), CardRef(GREETER))
        }

        "CR 608.2b: flickering an empty list of permanents is a legal no-op" {
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(GREETER)))
            val untouched = flickerPermanents(state, emptyList())
            untouched.sharedZones.battlefield shouldContainExactly state.sharedZones.battlefield
            untouched.pendingTriggers.shouldBeEmpty()
        }

        "CR 603.6c: exiling a permanent fires its leaves-the-battlefield trigger and not its graveyard one" {
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(WARDEN)))
            val warden = state.onePermanent(WARDEN)

            val exiled = exilePermanent(state, warden.id)

            // The Warden prints *both* departure conditions, so this is a discriminator and not a
            // tautology: CR 603.6b watches a graveyard, and an exile is not one.
            exiled.pendingTriggers.map { it.ability.condition } shouldContainExactly
                listOf(TriggerCondition.LeftBattlefieldSelf)
            exiled.sharedZones.exile
                .single()
                .card shouldBe CardRef(WARDEN)
            exiled.sharedZones.battlefield.shouldBeEmpty()
        }

        "CR 603.6b + CR 603.6c: a departure to a graveyard fires both conditions, the narrower one subjected" {
            val state = blinkState()
            val left = GameObject(DEPARTED_ID, CardRef(WARDEN), alice)

            val fired = announceBattlefieldDeparture(state, left, graveyardId = GRAVEYARD_ID)

            fired.pendingTriggers.map { it.ability.condition } shouldContainExactly
                listOf(
                    TriggerCondition.LeftBattlefieldSelf,
                    TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                )
            // CR 603.10: the graveyard trigger acts on the fresh graveyard object, so it carries it; the
            // general one acts on whatever its linked record names, so it carries no subject.
            fired.pendingTriggers[0].subject.shouldBeNull()
            fired.pendingTriggers[1].subject shouldBe GRAVEYARD_ID
            fired.pendingTriggers.forEach { it.sourceId shouldBe DEPARTED_ID }
        }

        "CR 607.2: two linked sources each record only the card they themselves exiled" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(battlefield = listOf(JAILER, JAILER)),
                    bobSeat = BlinkSeat(battlefield = listOf(OX, OX)),
                )
            val jailers = state.allPermanents(JAILER)
            val prey = state.allPermanents(OX)

            val first = exileLinkedToSource(state, prey[0].id, jailers[0].id)
            val firstExiled =
                first.sharedZones.exile
                    .last()
                    .id
            val second = exileLinkedToSource(first, prey[1].id, jailers[1].id)
            val secondExiled =
                second.sharedZones.exile
                    .last()
                    .id

            // The whole of CR 607.2: "the exiled card" is *this* source's, never the last card anyone
            // exiled and never the card the other source is holding.
            firstExiled shouldNotBe secondExiled
            second.permanent(jailers[0].id).linkedExiled shouldContainExactly listOf(firstExiled)
            second.permanent(jailers[1].id).linkedExiled shouldContainExactly listOf(secondExiled)
        }

        "CR 607.3: a linked exile whose source has already gone still exiles, and records nothing" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(battlefield = listOf(JAILER)),
                    bobSeat = BlinkSeat(battlefield = listOf(OX)),
                )
            val prey = state.onePermanent(OX)

            val exiled = exileLinkedToSource(state, prey.id, ABSENT_SOURCE_ID)

            // The first ability does what it was told; there is simply nothing left to link the result to.
            exiled.sharedZones.exile
                .single()
                .card shouldBe CardRef(OX)
            exiled.sharedZones.battlefield.forEach { it.linkedExiled.shouldBeEmpty() }
        }

        "CR 603.10: the linked record is captured into the fired trigger, so it survives the source leaving" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(battlefield = listOf(JAILER)),
                    bobSeat = BlinkSeat(battlefield = listOf(OX)),
                )
            val jailer = state.onePermanent(JAILER)
            val recorded = exileLinkedToSource(state, state.onePermanent(OX).id, jailer.id)
            val exiledCard =
                recorded.sharedZones.exile
                    .single()
                    .id

            val departed = exilePermanent(recorded, jailer.id)

            // By the time this ability resolves its source is gone, so the link must travel with the
            // trigger rather than be read off a permanent that no longer exists.
            val fired = departed.pendingTriggers.single()
            fired.ability.condition shouldBe TriggerCondition.LeftBattlefieldSelf
            fired.linkedExiled shouldContainExactly listOf(exiledCard)
            departed.sharedZones.battlefield.shouldBeEmpty()
        }

        "CR 400.7: returning an id that has already left exile does nothing at all" {
            val state = blinkState(aliceSeat = BlinkSeat(battlefield = listOf(OX)))

            // A card that has left exile is a different object; the CR-correct answer is to return nothing
            // rather than to guess which card the ability meant.
            returnExiledToBattlefield(state, ABSENT_EXILE_ID) shouldBe state
            returnExiledToOwnersHand(state, ABSENT_EXILE_ID) shouldBe state
        }

        "CR 109.5: target creature you control enumerates decider-relative, so each seat sees only its own" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(battlefield = listOf(GREETER)),
                    bobSeat = BlinkSeat(battlefield = listOf(OX)),
                )
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)

            // One board, two option lists: "you" is the choosing player, not a property of the battlefield.
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(state.onePermanent(GREETER).id))
            legalTargets(state, spec, bob, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(state.onePermanent(OX).id))
        }

        "CR 701.7a: an each-opponent discard is decided by the opponent, over the opponent's own hand" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(library = listOf(OX)),
                    bobSeat = BlinkSeat(hand = listOf(TRICK, WASTE)),
                    stack = listOf(abilityOnStack(eachOpponentDiscardsAbility)),
                )

            val paused = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseOpponentDiscards>()

            // `FW-NONCTRLDEC` in one assertion: the deciding seat is not the resolving object's controller,
            // and the options it is handed are its own hidden hand (CR 402.1).
            request.id.seat shouldBe bob
            request.controller shouldBe alice
            request.count shouldBe 1
            request.options.map { it.objectId } shouldContainExactly
                state.players
                    .getValue(bob)
                    .hand
                    .map { it.id }
        }

        "CR 701.7a: an opponent who cannot discard is skipped, and the controller draws instead" {
            val state =
                blinkState(
                    aliceSeat = BlinkSeat(library = listOf(OX)),
                    bobSeat = BlinkSeat(),
                    stack = listOf(abilityOnStack(eachOpponentDiscardsAbility)),
                )

            val finished = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

            // An empty hand cannot discard, so that seat is never asked at all (ADR-005), and the
            // "for each opponent who can't" draw happens instead.
            finished.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            finished.state.pendingOpponentDiscard.shouldBeNull()
            finished.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef(OX))
            finished.state.sharedZones.stack
                .shouldBeEmpty()
        }

        "CR 701.16a: the whole hand is revealed, and the choice belongs to the controller" {
            val state =
                blinkState(
                    bobSeat = BlinkSeat(hand = listOf(TRICK, WASTE, OX)),
                    stack = listOf(abilityOnStack(handRevealAbility(RevealedCardRestriction.NONLAND))),
                )

            val paused = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseRevealedHandCard>()

            // "Target opponent reveals their hand" is not a decision: all of it is revealed, including the
            // cards the restriction will not let the controller choose.
            paused.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards shouldContainExactly listOf(CardRef(TRICK), CardRef(WASTE), CardRef(OX))
            // "**You** choose": the deciding seat is the controller, and the revealer is the other seat.
            request.id.seat shouldBe alice
            request.revealer shouldBe bob
            request.options.map { it.card } shouldContainExactly listOf(CardRef(TRICK), CardRef(OX))
        }

        "CR 701.16a: a noncreature, nonland restriction offers neither a creature nor a land" {
            val state =
                blinkState(
                    bobSeat = BlinkSeat(hand = listOf(OX, WASTE, TRICK)),
                    stack =
                        listOf(
                            abilityOnStack(handRevealAbility(RevealedCardRestriction.NONCREATURE_NONLAND)),
                        ),
                )

            val paused = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseRevealedHandCard>()

            request.options.map { it.card } shouldContainExactly listOf(CardRef(TRICK))
        }

        "CR 701.16a: a hand with no legal choice surfaces no request, and the reveal still happens" {
            val state =
                blinkState(
                    bobSeat = BlinkSeat(hand = listOf(OX, WASTE)),
                    stack =
                        listOf(
                            abilityOnStack(handRevealAbility(RevealedCardRestriction.NONCREATURE_NONLAND)),
                        ),
                )

            val finished = resolveTopOfStack(state).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

            // ADR-005: an illegal option has no index, so an empty option list is *no* request rather than
            // an empty one. The information the printed reveal grants is granted anyway.
            finished.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            finished.state.pendingHandReveal.shouldBeNull()
            finished.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards shouldContainExactly listOf(CardRef(OX), CardRef(WASTE))
            finished.state.players
                .getValue(bob)
                .hand shouldHaveSize 2
            finished.state.sharedZones.stack
                .shouldBeEmpty()
        }

        "CR 702.88a: a rebound spell cast from a hand is exiled as it resolves, marked with its turn" {
            val state = blinkState(stack = listOf(spellOnStack(reboundSpell(TargetSpec.None), castVia = null)))

            val resolved = resolveTopOfStack(state).pausedState

            // Rebound replaces the CR 608.2m graveyard move rather than following it.
            resolved.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            val exiled = resolved.sharedZones.exile.single()
            exiled.card shouldBe CardRef(ECHO)
            exiled.reboundTurn shouldBe BLINK_TURN
        }

        "CR 702.88a: the rebounded cast comes from exile, so it finishes in the graveyard and the loop ends" {
            val state =
                blinkState(
                    stack = listOf(spellOnStack(reboundSpell(TargetSpec.None), castVia = CastingPermission.Rebound)),
                )

            val resolved = resolveTopOfStack(state).pausedState

            // "If this spell was cast from your hand" is the whole terminator: no guard, just the rule.
            resolved.sharedZones.exile.shouldBeEmpty()
            resolved.players
                .getValue(alice)
                .graveyard
                .single()
                .card shouldBe CardRef(ECHO)
        }

        "CR 702.88a: a fizzled rebound spell does not rebound — it goes to the graveyard" {
            val fizzling = reboundSpell(TargetSpec.TargetPermanent(PermanentRestriction.CREATURE))
            val state =
                blinkState(
                    stack =
                        listOf(
                            spellOnStack(
                                fizzling,
                                castVia = null,
                                targets = persistentListOf(Target.Permanent(ABSENT_TARGET_ID)),
                            ),
                        ),
                )

            val resolved = resolveTopOfStack(state).pausedState

            // CR 702.88a exiles the card "instead of putting it into your graveyard **as it resolves**",
            // and a spell that does not resolve never reaches that replacement.
            resolved.events.filterIsInstance<GameEvent.SpellFizzled>() shouldHaveSize 1
            resolved.sharedZones.exile.shouldBeEmpty()
            resolved.players
                .getValue(alice)
                .graveyard
                .single()
                .card shouldBe CardRef(ECHO)
        }
    })

/** The turn every scenario runs on; a rebound mark records it (CR 702.88a). */
private const val BLINK_TURN: Int = 3

/** Marked damage dressed onto a permanent before a flicker, to prove CR 400.7 discards it. */
private const val MARKED_DAMAGE: Int = 2

/** How many `+1/+1` counters the dressed permanent carries before a flicker (CR 122.2). */
private const val COUNTERS: Int = 2

/** "Blink Greeter" — a creature whose only ability is an enters-the-battlefield trigger (CR 603.6a). */
private const val GREETER: String = "Blink Greeter"

/** "Blink Ox" — a plain creature with no abilities, the inert body every scenario moves around. */
private const val OX: String = "Blink Ox"

/** "Blink Warden" — an enchantment printing **both** departure conditions, the CR 603.6b/c discriminator. */
private const val WARDEN: String = "Blink Warden"

/** "Blink Jailer" — an enchantment printing only the CR 603.6c condition, Journey to Nowhere's shape. */
private const val JAILER: String = "Blink Jailer"

/** "Blink Trick" — an instant, the noncreature nonland card a reveal restriction admits. */
private const val TRICK: String = "Blink Trick"

/** "Blink Waste" — a land, the card every reveal restriction in the pool excludes. */
private const val WASTE: String = "Blink Waste"

/** "Blink Echo" — the rebounding instant (CR 702.88); it is never on a battlefield. */
private const val ECHO: String = "Blink Echo"

/** The id a fixture departure is announced under — deliberately no object in any zone. */
private val DEPARTED_ID = ObjectId(700)

/** The fresh graveyard object a CR 603.6b departure carries as its subject (CR 400.7). */
private val GRAVEYARD_ID = ObjectId(701)

/** A linked-exile source that is not on the battlefield (CR 607.3). */
private val ABSENT_SOURCE_ID = ObjectId(702)

/** An exile id naming nothing, for the CR 400.7 no-op returns. */
private val ABSENT_EXILE_ID = ObjectId(703)

/** A permanent target that has already left the battlefield, for the CR 608.2b fizzle. */
private val ABSENT_TARGET_ID = ObjectId(704)

/** The id a fixture ability's source is remembered by (CR 603.10); no object on the board carries it. */
private val ABILITY_SOURCE_ID = ObjectId(710)

/** The stack-residence id of the rebounding spell's card (CR 400.7). */
private val SPELL_OBJECT_ID = ObjectId(711)

/** The allocation counter every fixture state starts from, above every handcrafted id. */
private const val BLINK_NEXT_ID: Long = 1000

/** A resolution that performs no instructions — every clause under test is the engine's, not an effect's. */
private val noInstructions = ResolutionEffect { state, _ -> state }

/** Refurbished Familiar's clause on a fixture trigger: each opponent discards one, else the controller draws. */
private val eachOpponentDiscardsAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = noInstructions,
        eachOpponentDiscards = EachOpponentDiscards(count = 1, drawPerOpponentWhoCannot = 1),
    )

/** Duress's and Mesmeric Fiend's clause on a fixture trigger, at whichever [restriction] is under test. */
private fun handRevealAbility(restriction: RevealedCardRestriction): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = noInstructions,
        targetSpec = TargetSpec.TargetOpponent,
        handRevealChoice = HandRevealChoice(restriction = restriction, outcome = RevealedCardOutcome.DISCARD),
    )

/** A fixture card definition: [name], the one card [type], and whatever triggered [abilities] it prints. */
private fun fixtureCard(
    name: String,
    type: CardType,
    abilities: List<TriggeredAbility> = emptyList(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                // CR 305.1: a land has no mana cost; everything else here is priced at one generic.
                manaCost = if (type == CardType.LAND) null else ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(type),
                subtypes = persistentSetOf(),
                // CR 208.1: a creature card, and only a creature card, has a printed power/toughness.
                powerToughness = if (type == CardType.CREATURE) PrintedPowerToughness(2, 2) else null,
            )
        override val triggeredAbilities = abilities.toPersistentList()
    }

/** A fixture instant printing rebound (CR 702.88a) and targeting per [spec]; its resolution does nothing. */
private fun reboundSpell(spec: TargetSpec): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = ECHO,
                manaCost = ManaCost.parse("{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = spec
        override val resolution = noInstructions
        override val rebound = true
    }

/** The fixture registry: the departure conditions live on the two enchantments, nothing else prints one. */
private val blinkDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        fixtureCard(
            GREETER,
            CardType.CREATURE,
            listOf(TriggeredAbility(TriggerCondition.EnteredBattlefieldSelf, noInstructions)),
        ),
        fixtureCard(OX, CardType.CREATURE),
        fixtureCard(
            WARDEN,
            CardType.ENCHANTMENT,
            listOf(
                TriggeredAbility(TriggerCondition.LeftBattlefieldSelf, noInstructions),
                TriggeredAbility(TriggerCondition.PutIntoGraveyardFromBattlefieldSelf, noInstructions),
            ),
        ),
        fixtureCard(
            JAILER,
            CardType.ENCHANTMENT,
            listOf(TriggeredAbility(TriggerCondition.LeftBattlefieldSelf, noInstructions)),
        ),
        fixtureCard(TRICK, CardType.INSTANT),
        fixtureCard(WASTE, CardType.LAND),
    ).associateBy { CardRef(it.characteristics.name) }

/** One seat's zones for [blinkState]; card names resolve through [blinkDefinitions]. */
private data class BlinkSeat(
    val battlefield: List<String> = emptyList(),
    val hand: List<String> = emptyList(),
    val library: List<String> = emptyList(),
)

/**
 * A handcrafted two-player state — a valid engine input by construction (ADR-004) — with alice active on
 * turn [BLINK_TURN], no priority round open, and [stack] resolving from the top down.
 */
private fun blinkState(
    aliceSeat: BlinkSeat = BlinkSeat(),
    bobSeat: BlinkSeat = BlinkSeat(),
    stack: List<StackEntry> = emptyList(),
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ): PersistentList<GameObject> =
        names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    fun seat(
        setup: BlinkSeat,
        owner: PlayerId,
    ): Pair<PlayerState, PersistentList<GameObject>> {
        val battlefield = objects(setup.battlefield, owner)
        val player =
            PlayerState(
                life = STARTING_LIFE,
                library = objects(setup.library, owner),
                hand = objects(setup.hand, owner),
                graveyard = persistentListOf(),
                priorityStatus = PriorityStatus.NONE,
            )
        return player to battlefield
    }

    val (alicePlayer, aliceField) = seat(aliceSeat, alice)
    val (bobPlayer, bobField) = seat(bobSeat, bob)
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = Turn(alice, BLINK_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = (aliceField + bobField).toPersistentList(),
                stack = stack.toPersistentList(),
                exile = persistentListOf(),
            ),
        nextObjectId = BLINK_NEXT_ID,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = blinkDefinitions.toPersistentMap(),
    )
}

/** Alice's fixture triggered [ability] resolving on the stack, targeting bob where its spec asks for a seat. */
private fun abilityOnStack(ability: TriggeredAbility): StackEntry.Ability =
    StackEntry.Ability(
        trigger = PendingTrigger(ABILITY_SOURCE_ID, CardRef(JAILER), alice, ability),
        targets =
            if (ability.targetSpec == TargetSpec.TargetOpponent) {
                persistentListOf(Target.Player(bob))
            } else {
                persistentListOf()
            },
    )

/** Alice's [definition] resolving on the stack, cast via [castVia] (`null` meaning cast from her hand). */
private fun spellOnStack(
    definition: SpellDefinition,
    castVia: CastingPermission?,
    targets: PersistentList<Target> = persistentListOf(),
): StackEntry.Spell =
    StackEntry.Spell(
        obj = GameObject(SPELL_OBJECT_ID, CardRef(definition.characteristics.name), alice),
        controller = alice,
        targets = targets,
        definition = definition,
        castVia = castVia,
    )

/** Every battlefield permanent of the fixture card [name], in battlefield order. */
private fun GameState.allPermanents(name: String): List<GameObject> =
    sharedZones.battlefield.filter { it.card == CardRef(name) }

/** The one battlefield permanent of the fixture card [name]; fails loudly if there is not exactly one. */
private fun GameState.onePermanent(name: String): GameObject = allPermanents(name).single()

/** The battlefield permanent with [id]; fails loudly if it has left. */
private fun GameState.permanent(id: ObjectId): GameObject = sharedZones.battlefield.single { it.id == id }
