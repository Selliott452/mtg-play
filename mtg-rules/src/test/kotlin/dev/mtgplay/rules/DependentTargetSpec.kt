package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
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
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.requireSliceableTargetLines
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
 * `W9-C`: the two shapes that make a target's legality depend on something other than the board, and the
 * one mechanism they share (docs/design/dependent-targets.md).
 *
 * - **A second targeting line that reads the first** — Searing Blaze's "target creature **that player**
 *   controls". Exercised through a fixture spell with two lines: the gathering must ask twice, in printed
 *   order, and the second question's option set must depend on the first answer.
 * - **A target restriction that reads an announced X** — Gorilla Shaman's "with mana value X". Exercised
 *   through a fixture ability: the announcement must come **first**, only payable-and-targetable values
 *   may be offered, and the CR 608.2b re-check must use the announced value rather than zero.
 *
 * `mtg-rules` names no card (ADR-003), so both are fixtures; the printed cards are pinned in `mtg-cards`.
 */
class DependentTargetSpec :
    StringSpec({
        val engine = DefaultGameEngine()
        val blaze = CardRef("Fixture Two-Line Blast")
        val eater = CardRef("Fixture Artifact Eater")

        // ---- a dependent second targeting line (CR 601.2c) -----------------------------------------

        "CR 601.2c: a spell printing two instances of 'target' surfaces two requests, in printed order" {
            val state = dependentState(bobCreatures = 1)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, castIndex(window, blaze)))

            // Line one: the players, in turn order — no permanent is ever offered for "target player".
            val first = current.pending<DecisionRequest.ChooseTargets>()
            first.options shouldContainExactly listOf(Target.Player(alice), Target.Player(bob))
            val bobIndex = first.options.indexOf(Target.Player(bob))
            current = engine.advance(current.pausedState, Decision.SingleSelect(first.id, bobIndex))

            // Line two: **only** the chosen player's creatures. The board also holds one of alice's, and
            // offering it would be an enumerated-but-illegal action (ADR-005) — the whole point.
            val second = current.pending<DecisionRequest.ChooseTargets>()
            second.options shouldContainExactly listOf(Target.Permanent(bobCreatureId(current.pausedState)))
        }

        "CR 601.2c: choosing the other player for line one changes line two's option set" {
            val state = dependentState(bobCreatures = 1)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, castIndex(window, blaze)))
            val first = current.pending<DecisionRequest.ChooseTargets>()
            current =
                engine.advance(
                    current.pausedState,
                    Decision.SingleSelect(first.id, first.options.indexOf(Target.Player(alice))),
                )
            val second = current.pending<DecisionRequest.ChooseTargets>()
            // alice's own creature, and only it — the dependence really is on the answer, not on the board.
            second.options shouldContainExactly listOf(Target.Permanent(aliceCreatureId(current.pausedState)))
        }

        "CR 601.2c: a two-line spell is not castable when no player has a targetable creature" {
            // Both halves exist in isolation — there are players, and there are no creatures — so a
            // per-line conjunction would still say "no". The interesting board is the next test.
            val state = dependentState(bobCreatures = 0, aliceCreatures = 0)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options.none { it is PriorityOption.CastSpell && it.card == blaze } shouldBe true
        }

        "CR 601.2c: the gate is a search — one player with one creature is enough, and it is found" {
            // The control for the negative case above, and the reason the gate cannot be a conjunction:
            // it has to find the *pair* (bob, bob's creature). Only one seat has a creature here, so a
            // gate that asked "is some player targetable?" and "is some creature targetable?" separately
            // would give the same answer for the wrong reason — the next test is the one that separates
            // them.
            val state = dependentState(bobCreatures = 1, aliceCreatures = 0)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options.any { it is PriorityOption.CastSpell && it.card == blaze } shouldBe true
        }

        "CR 608.2b: a two-line spell whose creature died still resolves against the player" {
            val state = dependentState(bobCreatures = 1)
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, castIndex(window, blaze)))
            val first = current.pending<DecisionRequest.ChooseTargets>()
            current =
                engine.advance(
                    current.pausedState,
                    Decision.SingleSelect(first.id, first.options.indexOf(Target.Player(bob))),
                )
            val second = current.pending<DecisionRequest.ChooseTargets>()
            current = engine.advance(current.pausedState, Decision.SingleSelect(second.id, 0))
            current = engine.advance(current.pausedState, planDecision(current.pending()))

            // Remove the creature out from under the spell, then let both seats pass so it resolves.
            val destroyed = destroy(current.pausedState, bobCreatureId(current.pausedState))
            var after = engine.advance(destroyed, passDecision(pausedRequestOf(destroyed)))
            after = engine.advance(after.pausedState, passDecision(after.pending()))
            // CR 608.2b: *some* target is still legal, so the spell resolved rather than fizzling — the
            // whole-spell verdict, not a per-line one.
            after.pausedState.sharedZones.stack
                .none { it is StackEntry.Spell } shouldBe true
            after.pausedState.events
                .any { it is GameEvent.SpellResolved } shouldBe true
            after.pausedState.events
                .none { it is GameEvent.SpellFizzled } shouldBe true
        }

        // ---- X on an activated ability, announced before targets (CR 601.2b) ------------------------

        "CR 601.2b: an ability whose cost carries {X} announces the value before it chooses targets" {
            val state = xState(artifactManaValues = listOf(2))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex(window, eater)))
            // The very first request is the announcement, not the target choice — CR 601.2b's printed order.
            val announcement = current.pending<DecisionRequest.ChooseXValue>()
            // Six lands: {X}{X}{1} is payable at X = 0 and X = 2, but only X = 2 has a legal target, so
            // only X = 2 is offered (ADR-005 — an announcement must never dead-end at CR 601.2c).
            announcement.values shouldContainExactly listOf(2)
            current = engine.advance(current.pausedState, Decision.SingleSelect(announcement.id, 0))
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            targets.options.size shouldBe 1
        }

        "ADR-005: an X ability with no payable-and-targetable value is not enumerated at all" {
            // One artifact of mana value 9: the target exists but X = 9 costs 19 mana, which six lands
            // cannot pay. Offering the activation would strand the seat mid-gathering.
            val state = xState(artifactManaValues = listOf(9))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            window.options.none { it is PriorityOption.ActivateAbility && it.card == eater } shouldBe true
        }

        "CR 202.3b: only artifacts whose printed mana value equals the announced X are offered" {
            val state = xState(artifactManaValues = listOf(0, 2))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex(window, eater)))
            val announcement = current.pending<DecisionRequest.ChooseXValue>()
            announcement.values shouldContainExactly listOf(0, 2)
            // Announce 0 and exactly the mana-value-0 artifact is offered; the mana-value-2 one is not.
            current = engine.advance(current.pausedState, Decision.SingleSelect(announcement.id, 0))
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            targets.options shouldContainExactly listOf(Target.Permanent(artifactId(current.pausedState, 0)))
        }

        "CR 601.2b: the announced value rides on the stack entry, so the CR 608.2b re-check asks the same question" {
            val state = xState(artifactManaValues = listOf(2))
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(state)
            var current = engine.advance(state, Decision.SingleSelect(window.id, activateIndex(window, eater)))
            val announcement = current.pending<DecisionRequest.ChooseXValue>()
            current = engine.advance(current.pausedState, Decision.SingleSelect(announcement.id, 0))
            val targets = current.pending<DecisionRequest.ChooseTargets>()
            current = engine.advance(current.pausedState, Decision.SingleSelect(targets.id, 0))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val entry =
                current.pausedState.sharedZones.stack
                    .last() as StackEntry.ActivatedAbilityOnStack
            // Recorded rather than recomputed: without it the re-check would compare against X = 0 and
            // fizzle an activation that is perfectly legal.
            entry.chosenX shouldBe 2
        }

        // ---- the gates the frameworks keep loud ------------------------------------------------------

        "CR 601.2c: a multi-line spell whose lines are not all fixed-count is refused rather than guessed" {
            val ambiguous =
                twoLineSpell(
                    "Fixture Ambiguous",
                    second = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE, TargetCount.UpTo(2)),
                )
            shouldThrow<IllegalArgumentException> {
                requireSliceableTargetLines(
                    ambiguous.characteristics.name,
                    listOf(ambiguous.targetSpec) + ambiguous.additionalTargetSpecs,
                )
            }
        }

        "TargetContext.NONE fails closed: a dependent restriction with no earlier answer names nothing" {
            // The conservative direction. An enumeration made with no context under-offers, which a test
            // catches; over-offering would hand an agent an illegal option, which ADR-005 forbids and
            // which nothing downstream would notice.
            val state = dependentState(bobCreatures = 1)
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER)
            legalTargets(state, spec, alice, Chooser.Nobody, TargetContext.NONE) shouldBe emptyList()
            // ...and names exactly that player's creatures once an earlier answer exists.
            val context = TargetContext(earlierTargets = persistentListOf(Target.Player(bob)))
            legalTargets(state, spec, alice, Chooser.Nobody, context) shouldContainExactly
                listOf(Target.Permanent(bobCreatureId(state)))
        }
    })

private fun castIndex(
    window: DecisionRequest.ChooseAction,
    card: CardRef,
): Int = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == card }

private fun activateIndex(
    window: DecisionRequest.ChooseAction,
    card: CardRef,
): Int = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == card }

private fun bobCreatureId(state: GameState): ObjectId =
    state.sharedZones.battlefield
        .first { it.card == CardRef("Fixture Bear") && it.owner == bob }
        .id

private fun aliceCreatureId(state: GameState): ObjectId =
    state.sharedZones.battlefield
        .first { it.card == CardRef("Fixture Bear") && it.owner == alice }
        .id

private fun artifactId(
    state: GameState,
    manaValue: Int,
): ObjectId =
    state.sharedZones.battlefield
        .first { it.card == CardRef("Fixture Rock $manaValue") }
        .id

/** A fixture spell printing two instances of the word "target", the second dependent on the first. */
private fun twoLineSpell(
    name: String,
    second: TargetSpec,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{R}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPlayer(TargetCount.ONE)
        override val additionalTargetSpecs = persistentListOf(second)
        override val resolution = ResolutionEffect { s, _ -> s }
    }

private val twoLineBlast =
    twoLineSpell(
        "Fixture Two-Line Blast",
        TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER),
    )

/** A fixture ability costing `{X}{X}{1}` whose target restriction reads the announced value. */
private val artifactEater: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Artifact Eater",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(1, 1),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{X}{X}{1}"))),
                    targetSpec =
                        TargetSpec.TargetPermanent(PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X),
                    effect =
                        ResolutionEffect { s, ctx ->
                            destroy(s, (ctx.targets.single() as Target.Permanent).id)
                        },
                ),
            )
    }

private fun rockDef(manaValue: Int): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Rock $manaValue",
                manaCost = ManaCost.parse("{$manaValue}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
    }

private val bearDef: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Bear",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(2, 2),
            )
    }

private val redLand: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Dependent Mountain",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
    }

private val dependentRegistry: Map<CardRef, CardDefinition> =
    buildMap {
        put(CardRef("Fixture Two-Line Blast"), twoLineBlast)
        put(CardRef("Fixture Artifact Eater"), artifactEater)
        put(CardRef("Fixture Bear"), bearDef)
        put(CardRef("Dependent Mountain"), redLand)
        (0..9).forEach { put(CardRef("Fixture Rock $it"), rockDef(it)) }
    }

private fun boardState(
    aliceBattlefield: List<Pair<CardRef, PlayerId>>,
    aliceHand: List<CardRef>,
): GameState {
    var nextId = 0L

    fun obj(
        card: CardRef,
        owner: PlayerId,
    ) = GameObject(ObjectId(nextId), card, owner).also { nextId += 1 }

    val field = aliceBattlefield.map { (card, owner) -> obj(card, owner) }.toPersistentList()
    val hand = aliceHand.map { obj(it, alice) }.toPersistentList()
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
        sharedZones = SharedZones(field, persistentListOf(), persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = dependentRegistry.toPersistentMap(),
    )
}

private fun dependentState(
    bobCreatures: Int,
    aliceCreatures: Int = 1,
): GameState =
    boardState(
        aliceBattlefield =
            List(2) { CardRef("Dependent Mountain") to alice } +
                List(aliceCreatures) { CardRef("Fixture Bear") to alice } +
                List(bobCreatures) { CardRef("Fixture Bear") to bob },
        aliceHand = listOf(CardRef("Fixture Two-Line Blast")),
    )

private fun xState(artifactManaValues: List<Int>): GameState =
    boardState(
        aliceBattlefield =
            List(6) { CardRef("Dependent Mountain") to alice } +
                listOf(CardRef("Fixture Artifact Eater") to alice) +
                artifactManaValues.map { CardRef("Fixture Rock $it") to alice },
        aliceHand = emptyList(),
    )
