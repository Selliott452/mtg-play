package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.CountCondition
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's cost-modification cards: the first spells whose total cost is not their printed cost
 * (CR 601.2f, docs/design/cost-modification.md). Grixis Affinity's [myrEnforcer], [thoughtcast], and
 * [utromMonitor]; Mono-Blue Terror's [crypticSerpent]; Mono Blue Faeries' [ofOneMind].
 *
 * Two shapes, both declared and neither implemented here — `mtg-rules` owns the CR 601.2f arithmetic,
 * the lock-in, and the floor at `{0}`:
 *
 * - **Affinity for artifacts** (CR 702.41a) and the Terrors' graveyard clause are counts, declared as
 *   [CostReduction.PerMatching]. Affinity is a static ability of the *spell*, functioning while the
 *   spell is on the stack, so it reduces the cost of casting the card and nothing else.
 * - **Of One Mind**'s "{2} less if you control a Human creature and a non-Human creature" is a flat
 *   amount gated on a board condition, declared as [CostReduction.IfAll]. Its second condition is the
 *   pool's first *negated* predicate, and the negation is over the printed subtype line.
 *
 * **Five siblings from the same `FW-COST` triage rows stay absent**, each on a framework this packet
 * does not own; every one of them is a card whose *cost* half is encodable and whose other half is not:
 *
 * - **Tolarian Terror** prints the identical graveyard clause to [crypticSerpent] and adds **ward {2}**
 *   (CR 702.21a) — a *triggered* pay-or-be-countered ability, not a cost increase. It needs `FW-WARD`
 *   on top of the counter framework. Cryptic Serpent carries the clause here instead, which is what
 *   the design note's §0 recommended once the oracle text was re-read.
 * - **Refurbished Familiar** was listed here as blocked on `FW-NONCTRLDEC` — a discard the *opponent*
 *   chooses, which no decision request in this engine could surface. That framework has since landed
 *   (docs/design/exile-and-return.md §6) and the card is encoded in `ExileAndReturn.kt`, reusing this
 *   file's [affinityForArtifacts] unchanged: its cost half needed nothing further.
 * - **Deem Inferior** reduces "for each card you've drawn this turn": a **turn-scoped event count** the
 *   state does not track at all, and its effect lets the *owner* choose a library position
 *   (`FW-NONCTRLDEC`).
 * - **Ride's End** was listed here as blocked on `FW-TGTCOND`: "{3} less if it targets a tapped
 *   permanent" is a cost that depends on the chosen target, while cast *legality* is decided before any
 *   target is chosen. That framework landed with `W8-C` and the card is encoded in `BurnAndRemoval.kt`.
 *   The diagnosis was right on both halves — the gate now prices the cheapest achievable target choice
 *   (`cheapestTargetsFor`), which is exactly the "exists a target making this affordable" test this
 *   entry asked for — and it turned out to need a second half nobody had written down: once the gate
 *   admits a cast only some of whose targets are payable, the *target request* has to be narrowed too,
 *   or the caster is offered an option that dead-ends at an empty payment plan (`affordableTargetOptions`).
 * - **Sunscape Familiar** is the other-object reducer this framework's C6 half exists for, and its
 *   declaration slot ([dev.mtgplay.core.definition.CardDefinition.spellCostReductions]) ships and is
 *   exercised by rules fixtures. The card itself stays absent because it prints **Defender**, a keyword
 *   `mtg-core` does not have (`FW-DEFENDERKW` — the same absence that keeps Overgrown Battlement out of
 *   ManaCreatures.kt). Encoding it without Defender would put a 0/3 Wall in the pool that can attack.
 */

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. The three affinity creatures' printed work is
 * their cost reduction, never a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** The cards Thoughtcast and Of One Mind each draw (CR 121.1). */
const val COST_REDUCTION_DRAW: Int = 2

/** Of One Mind's reduction when both halves of its board condition are met (CR 601.2f). */
const val OF_ONE_MIND_REDUCTION: Int = 2

/** The creature type Of One Mind's two conditions are stated for and against (CR 205.3m). */
private val HUMAN: Subtype = Subtype("Human")

/**
 * **Affinity for artifacts** (CR 702.41a): "This spell costs {1} less to cast for each artifact you
 * control."
 *
 * A static ability of the spell that functions while it is on the stack (CR 702.41a says so
 * explicitly), counting battlefield permanents with the artifact card type under the caster's control
 * (CR 403). The spell being cast is never among them — CR 601.2a has moved it out of the hand and onto
 * the stack before the cost is determined — which matters for [myrEnforcer] and [utromMonitor], which
 * are themselves artifacts: a *resolved* copy counts, the one being cast never does.
 *
 * Shared by all three of this file's affinity cards; identical text, identical declaration — and,
 * since `FW-NONCTRLDEC`, by [refurbishedFamiliar] in `ExileAndReturn.kt`, which is why this is
 * `internal` rather than `private`. One printed line gets one declaration: a second copy would be a
 * second place for CR 702.41a to be got wrong.
 */
internal val affinityForArtifacts: CostReduction =
    CostReduction.PerMatching(
        amountPerMatch = 1,
        scope = CountScope.BATTLEFIELD_YOU_CONTROL,
        predicate = ObjectPredicate.HasCardType(CardType.ARTIFACT),
    )

/**
 * Myr Enforcer — `{7}` Artifact Creature — Myr, a 4/4. "Affinity for artifacts."
 *
 * The archetypal affinity card and the reason the `{0}` floor is not a theoretical concern: with seven
 * artifacts out its total cost is `{0}`, a real one-symbol cost that enumerates exactly one payment
 * plan (the empty one). With eight or more it is still `{0}` — CR 601.2f's "it can't be reduced to less
 * than {0}".
 *
 * Colourless with no coloured pip anywhere in the cost, so unlike [crypticSerpent] there is no
 * CR 118.7a floor above zero.
 */
val myrEnforcer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Myr Enforcer",
                manaCost = ManaCost.parse("{7}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Myr")),
                powerToughness = PrintedPowerToughness(power = 4, toughness = 4),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val costReduction = affinityForArtifacts
    }

/**
 * Utrom Monitor — `{4}{U}` Artifact Creature — Utrom Scientist, a 3/3. "Affinity for artifacts.
 * Flying."
 *
 * [myrEnforcer]'s coloured sibling, and the card that shows CR 118.7a's floor on an artifact body: the
 * `{U}` survives any number of artifacts, so the cheapest this can ever be cast for is `{U}`. Flying
 * is a plain printed keyword (CR 702.9) the combat rules already read.
 */
val utromMonitor: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Utrom Monitor",
                manaCost = ManaCost.parse("{4}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Utrom"), Subtype("Scientist")),
                powerToughness = PrintedPowerToughness(power = 3, toughness = 3),
                keywords = persistentSetOf(Keyword.FLYING),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val costReduction = affinityForArtifacts
    }

/**
 * Thoughtcast — `{4}{U}` Sorcery. "Affinity for artifacts. Draw two cards."
 *
 * The affinity card that is *not* an artifact, which is the point of including it beside
 * [myrEnforcer]: nothing about the reduction depends on the spell sharing a type with what it counts,
 * and the spell never counts itself in either case. Floors at `{U}` by CR 118.7a.
 */
val thoughtcast: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Thoughtcast",
                manaCost = ManaCost.parse("{4}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> drawCards(state, context.controller, COST_REDUCTION_DRAW) }
        override val costReduction = affinityForArtifacts
    }

/**
 * Cryptic Serpent — `{5}{U}{U}` Creature — Serpent, a 6/5. "This spell costs {1} less to cast for each
 * instant and sorcery card in your graveyard."
 *
 * The clean representative of the graveyard-count clause. It counts **cards in a graveyard** (CR 404),
 * not objects on the stack, and the count is taken once at CR 601.2f and locked in: a card put into the
 * graveyard by a later cost-payment stage does not make it cheaper, and escape fodder exiled at
 * CR 601.2h was already counted.
 *
 * `{U}{U}` is the floor whatever the graveyard holds (CR 118.7a) — a generic reduction cannot touch a
 * coloured pip — which is the property this card exists in the tests to pin.
 *
 * Its twin **Tolarian Terror** prints the same clause and is deliberately absent: ward {2} (CR 702.21a)
 * is a triggered ability, not a cost, and needs a framework this packet does not own.
 */
val crypticSerpent: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Cryptic Serpent",
                manaCost = ManaCost.parse("{5}{U}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Serpent")),
                powerToughness = PrintedPowerToughness(power = 6, toughness = 5),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val costReduction =
            CostReduction.PerMatching(
                amountPerMatch = 1,
                scope = CountScope.YOUR_GRAVEYARD,
                // "each instant and sorcery card" is one card matching *either* type, not a card
                // matching both — no card is simultaneously an instant and a sorcery, and reading the
                // "and" conjunctively would make the reduction permanently zero.
                predicate =
                    ObjectPredicate.AnyOf(
                        persistentListOf(
                            ObjectPredicate.HasCardType(CardType.INSTANT),
                            ObjectPredicate.HasCardType(CardType.SORCERY),
                        ),
                    ),
            )
    }

/**
 * Of One Mind — `{2}{U}` Sorcery. "This spell costs {2} less to cast if you control a Human creature
 * and a non-Human creature. Draw two cards."
 *
 * The pool's first **conditional flat** reduction: not a count but a gate, and worth `{2}` or nothing
 * with no value in between. Both halves are read at CR 601.2f against battlefield permanents the caster
 * controls, and the second is the first **negated** predicate in the pool — "a non-Human creature" is a
 * creature that does *not* have the Human subtype (CR 205.3), which is a different test from "not a
 * Human creature".
 *
 * The two conditions are independent counts, so a single creature cannot satisfy both — nothing is both
 * Human and non-Human — and two distinct creatures are genuinely required. That falls out of the
 * predicates rather than needing a disjointness rule, because no creature matches both.
 */
val ofOneMind: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Of One Mind",
                manaCost = ManaCost.parse("{2}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context -> drawCards(state, context.controller, COST_REDUCTION_DRAW) }
        override val costReduction =
            CostReduction.IfAll(
                amount = OF_ONE_MIND_REDUCTION,
                conditions =
                    persistentListOf(
                        CountCondition(
                            scope = CountScope.BATTLEFIELD_YOU_CONTROL,
                            predicate =
                                ObjectPredicate.And(
                                    persistentListOf(
                                        ObjectPredicate.HasCardType(CardType.CREATURE),
                                        ObjectPredicate.HasSubtype(HUMAN),
                                    ),
                                ),
                            atLeast = 1,
                        ),
                        CountCondition(
                            scope = CountScope.BATTLEFIELD_YOU_CONTROL,
                            predicate =
                                ObjectPredicate.And(
                                    persistentListOf(
                                        ObjectPredicate.HasCardType(CardType.CREATURE),
                                        ObjectPredicate.Not(ObjectPredicate.HasSubtype(HUMAN)),
                                    ),
                                ),
                            atLeast = 1,
                        ),
                    ),
            )
    }
