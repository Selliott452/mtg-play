package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.StackTargetTax
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.CastSubject
import dev.mtgplay.rules.engine.increaseGeneric
import dev.mtgplay.rules.engine.reduceGeneric
import dev.mtgplay.rules.engine.totalCost
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W10-D`: the cost **increase** (CR 601.2f step 4) and the legality gate it forces — Kaervek's Torch's
 * *"spells that target it cost `{2}` more to cast"*, without its name (ADR-003).
 *
 * The claims, and each is a recorded failure mode rather than a hypothetical:
 * 1. the arithmetic — an increase folds into the generic component and renders where the card prints it;
 * 2. the increase is applied **before** the reduction (CR 601.2f), which the `{0}` clamp makes observable;
 * 3. the tax is keyed on the *chosen target*: a counter aimed elsewhere pays nothing;
 * 4. **the gate**: a seat that cannot afford the tax is never offered the cast at all (ADR-005) — the
 *    position `W9-C` predicted would crash, because the old gate admitted the cast and the filter then
 *    emptied its option list;
 * 5. a seat that *can* afford it is offered the cast, and the target request offers the taxing spell —
 *    the other direction of ADR-005, which a gate that simply refused everything would also satisfy;
 * 6. every other cast in the same window is priced exactly as it was, tax or no tax.
 */
class StackTargetTaxSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val bob = PlayerId(1)

        "CR 601.2f: an increase folds into the generic component, where the card prints it" {
            increaseGeneric(ManaCost.parse("{U}{U}"), 2).render() shouldBe "{2}{U}{U}"
            increaseGeneric(ManaCost.parse("{1}{U}"), 2).render() shouldBe "{3}{U}"
            // The single Generic(0) a full reduction leaves behind absorbs the tax rather than sitting
            // beside it: {0} taxed by two is {2}, never {0}{2}.
            increaseGeneric(ManaCost.parse("{0}"), 2).render() shouldBe "{2}"
            increaseGeneric(ManaCost.parse("{U}{U}"), 0).render() shouldBe "{U}{U}"
        }

        "CR 601.2f: increases are applied before reductions, and the {0} clamp makes that observable" {
            // The order is a rule rather than a convention, which is why  applies them this
            // way round and why 's order-independence comment had to be amended.
            reduceGeneric(increaseGeneric(ManaCost.parse("{1}"), 2), 3).render() shouldBe "{0}"
            // The other order clamps first and then taxes back up — a different, wrong cost.
            increaseGeneric(reduceGeneric(ManaCost.parse("{1}"), 3), 2).render() shouldBe "{2}"
        }

        "CR 601.2f: the tax applies only when the chosen target is the taxing spell" {
            val state = taxState(islands = 4)
            val torch =
                state.sharedZones.stack
                    .filterIsInstance<StackEntry.Spell>()
                    .single()
            val counter =
                state.players
                    .getValue(bob)
                    .hand
                    .first { it.card == COUNTER }

            fun priced(targets: List<Target>) =
                totalCost(
                    state,
                    bob,
                    CastSubject(fixtureTaxCounter, permission = null, castObjectId = counter.id, targets = targets),
                ).render()
            priced(emptyList()) shouldBe "{U}{U}"
            priced(listOf(Target.SpellOnStack(torch.obj.id))) shouldBe "{2}{U}{U}"
            // A target that is not the taxing spell costs nothing extra, which is what "spells that
            // target **it**" means.
            priced(listOf(Target.SpellOnStack(ObjectId(9999)))) shouldBe "{U}{U}"
        }

        "ADR-005: a seat that cannot pay the tax is never offered the cast" {
            // Two Islands: {U}{U} is payable, {2}{U}{U} is not, and the taxing spell is the only legal
            // target. The old gate priced this at {U}{U}, admitted it, and then handed `targetRequest` an
            // empty option list — a crash. The window must simply not contain the cast.
            val request = pausedRequestOf<DecisionRequest.ChooseAction>(taxState(islands = 2))
            request.options.none { it is PriorityOption.CastSpell && it.card == COUNTER } shouldBe true
            // And deriving the window at all is the assertion: an empty-options `ChooseTargets` throws.
            shouldNotThrowAny { pausedRequestOf<DecisionRequest.ChooseAction>(taxState(islands = 2)) }
        }

        "ADR-005: a seat that can pay the tax is offered the cast, and the taxing spell as its target" {
            val state = taxState(islands = 4)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == COUNTER }
            (index >= 0) shouldBe true
            val chosen = engine.advance(state, Decision.SingleSelect(window.id, index))
            // CR 601.2c comes before CR 601.2f: the targets are chosen first, and the taxing spell
            // survives the affordability filter because four mana covers {2}{U}{U}.
            val targets = chosen.pending<DecisionRequest.ChooseTargets>()
            val torch =
                state.sharedZones.stack
                    .filterIsInstance<StackEntry.Spell>()
                    .single()
            targets.options shouldBe listOf(Target.SpellOnStack(torch.obj.id))
            // And the payment plan that follows is derived against the *taxed* cost, so it exists.
            val aimed = engine.advance(chosen.pausedState, Decision.SingleSelect(targets.id, 0))
            aimed.pending<DecisionRequest.ChoosePaymentPlan>().options.isNotEmpty() shouldBe true
        }

        "CR 601.2b: a modal card keeps the mode it can pay for and loses the one it cannot" {
            // The shape the pool actually prints (Pyroblast, the Elemental Blasts): "counter target
            // spell, or destroy target permanent". With two Islands the counter mode would cost
            // {2}{U} and the other {U}, so exactly one mode may be offered — and offering the taxed
            // one anyway would dead-end at an empty target list (ADR-005).
            val state = taxState(islands = 2)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == BLAST }
            (index >= 0) shouldBe true
            val chosen = engine.advance(state, Decision.SingleSelect(window.id, index))
            val modes = chosen.pending<DecisionRequest.ChooseModes>()
            modes.options.map { it.modeIndex } shouldBe listOf(BLAST_PERMANENT_MODE)
        }

        "CR 601.2b: with the tax affordable, the modal card keeps both modes" {
            val state = taxState(islands = 4)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == BLAST }
            val chosen = engine.advance(state, Decision.SingleSelect(window.id, index))
            chosen.pending<DecisionRequest.ChooseModes>().options.map { it.modeIndex } shouldBe
                listOf(BLAST_COUNTER_MODE, BLAST_PERMANENT_MODE)
        }

        "CR 601.2f: a cast that cannot name a spell is priced identically with the tax live" {
            // The containment property the gate's pre-check rests on: nothing but a spell that can point
            // at a spell is affected, so the untargeted fixture is offered on two Islands exactly as it
            // would be with an empty stack.
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(taxState(islands = 2))
            window.options.any { it is PriorityOption.CastSpell && it.card == CANTRIP } shouldBe true
        }
    })

private val TORCH: CardRef = CardRef("Fixture Tax Spell")
private val COUNTER: CardRef = CardRef("Fixture Tax Counter")
private val CANTRIP: CardRef = CardRef("Fixture Tax Cantrip")
private val ISLAND: CardRef = CardRef("Fixture Tax Island")
private val BLAST: CardRef = CardRef("Fixture Tax Blast")

/** The index of the fixture blast's counter mode, and of its destroy mode. */
private const val BLAST_COUNTER_MODE: Int = 0
private const val BLAST_PERMANENT_MODE: Int = 1

/** The generic mana the fixture taxing spell adds to a spell that targets it. */
private const val FIXTURE_TAX: Int = 2

private val fixtureTaxSpell: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Tax Spell",
                manaCost = ManaCost.parse("{X}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val stackTargetTax = StackTargetTax(FIXTURE_TAX)
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val fixtureTaxCounter: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Tax Counter",
                manaCost = ManaCost.parse("{U}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any)
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val fixtureTaxCantrip: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Tax Cantrip",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { s, _ -> s }
    }

/**
 * A modal `{U}` instant — "counter target spell, **or** destroy target permanent" — which is the shape
 * Pyroblast and the Elemental Blasts print and the reason the tax has to narrow a *mode* rather than
 * refuse a modal card outright.
 */
private val fixtureTaxBlast: SpellDefinition =
    object : ModalSpell {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Tax Blast",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any),
                    resolution = ResolutionEffect { s, _ -> s },
                ),
                SpellMode(
                    text = "Destroy target permanent.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT),
                    resolution = ResolutionEffect { s, _ -> s },
                ),
            )
    }

private val fixtureTaxIsland: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Tax Island",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.BLUE)))
    }

private val taxRegistry: Map<CardRef, CardDefinition> =
    mapOf(
        TORCH to fixtureTaxSpell,
        COUNTER to fixtureTaxCounter,
        CANTRIP to fixtureTaxCantrip,
        BLAST to fixtureTaxBlast,
        ISLAND to fixtureTaxIsland,
    )

/**
 * Alice's taxing spell on the stack with X announced, bob holding priority with a counter and a cantrip
 * in hand and [islands] untapped lands — the position the whole tax exists for.
 */
private fun taxState(islands: Int): GameState {
    var nextId = 0L

    fun obj(
        card: CardRef,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId), card, owner).also { nextId += 1 }

    val bob = PlayerId(1)
    val torch = obj(TORCH, alice)
    val field = List(islands) { obj(ISLAND, bob) }.toPersistentList()
    val hand = persistentListOf(obj(COUNTER, bob), obj(CANTRIP, bob), obj(BLAST, bob))
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = hand,
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = field,
                stack =
                    persistentListOf(
                        StackEntry.Spell(
                            obj = torch,
                            controller = alice,
                            targets = persistentListOf(Target.Player(bob)),
                            definition = fixtureTaxSpell,
                            chosenX = 3,
                        ),
                    ),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = taxRegistry.toPersistentMap(),
    )
}
