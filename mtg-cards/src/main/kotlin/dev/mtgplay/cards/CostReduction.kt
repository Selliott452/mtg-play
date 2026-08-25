package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellCostReduction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The **other-object** cost reducer (CR 604.5, CR 601.2f): a battlefield permanent that makes somebody
 * else's spells cheaper. `FW-COST`'s C6 half built the declaration slot
 * ([dev.mtgplay.core.definition.CardDefinition.spellCostReductions]) and named this exact card in its
 * KDoc; the card itself stayed out because it prints **Defender**, and `Keyword.DEFENDER` did not exist
 * yet. `FW-COUNTERS` added the keyword with its real CR 702.3b effect, and this file is the promise
 * being kept.
 *
 * It is deliberately separate from CostReductionCards.kt, which holds the *self*-referential shape —
 * affinity and the Terrors' graveyard count, declared on
 * [dev.mtgplay.core.definition.SpellDefinition.costReduction]. The two are the design note's §1 split:
 * a spell's own static ability functioning while it is on the stack, versus a permanent's static
 * ability functioning on the battlefield and reading a *different* object. Same CR 601.2f hook, same
 * arithmetic, different reader and different subject — which is why they are two declaration slots and
 * why they read as two files.
 *
 * **The clause's colours are read from the spell's printed mana cost, never the cost being paid**
 * (CR 202.2). `mtg-rules` owns that, and it is the one line of the framework that is invisible when it
 * is wrong: a plot cast's `{0}` alternative cost has no colours at all, so pricing a madness,
 * flashback, escape, or plot cast off the alternative would silently stop this Familiar reducing
 * anything.
 *
 * **Tolarian Terror stays out and is not this file's shape anyway.** Its printed cost clause is
 * [crypticSerpent]'s exactly — `CostReduction.PerMatching(1, YOUR_GRAVEYARD, AnyOf(INSTANT, SORCERY))`
 * — and would ship today. What keeps it out is **ward {2}** (CR 702.21a), which is a *triggered*
 * ability that counters the targeting spell or ability unless its controller pays; the packet report
 * records what `FW-WARD` needs.
 */

/** Sunscape Familiar's printed toughness (CR 208.2) — a 0/3 Wall. */
private const val SUNSCAPE_FAMILIAR_TOUGHNESS: Int = 3

/** The generic mana Sunscape Familiar takes off a matching spell (CR 601.2f, CR 118.7a). */
private const val SUNSCAPE_FAMILIAR_REDUCTION: Int = 1

/**
 * CR 608.3: a permanent spell with no instructions of its own resolves by entering the battlefield.
 * All of the Familiar's printed work is done by the permanent, never by the spell.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Sunscape Familiar — `{1}{W}` Creature — Wall, a 0/3 with defender and "Green spells and blue spells
 * you cast cost `{1}` less to cast."
 *
 * UWX Familiar's engine, and the first *permanent* in the pool that changes what another spell costs.
 * Four readings of the printed line matter, and each is a distinct way to get it wrong:
 *
 * - **It is white and reduces green and blue.** The Familiar shares no colour with anything it helps —
 *   there is no "spells that share a colour with this" rule hiding in the text, and reading the
 *   reduction off the reducer's own colour would make the card do nothing at all.
 * - **"Green spells **and** blue spells" is a disjunction over the spell's colours** (CR 202.2), not a
 *   demand for both: a mono-green spell qualifies, a mono-blue spell qualifies, and a Simic gold spell
 *   qualifies once. [SpellCostReduction.spellColors] is a set the engine tests with "any of".
 * - **`{1}` less is generic-only** (CR 118.7a). A `{G}{G}` spell costs `{G}{G}`; the reduction has
 *   nothing to bite on and is not allowed to eat a coloured pip. `mtg-rules` owns that floor.
 * - **It is a static ability of the permanent, applied once per matching Familiar** (CR 604.5), and it
 *   is locked in at CR 601.2f. Two Familiars reduce by two; sacrificing the Familiar as an additional
 *   cost *after* the total cost was determined still pays the reduced cost — which is the CR 601.2h
 *   worked example, printed about this card's own sibling.
 *
 * **Defender is a real ability here, not decoration** (CR 702.3b). The Wall is a 0/3 that genuinely
 * cannot attack, and `eligibleAttackers` never offers it; it blocks normally. Encoding the card before
 * `Keyword.DEFENDER` existed would have put a 0/3 attacker in the pool, which is exactly why `FW-COST`
 * shipped the framework without the card.
 */
val sunscapeFamiliar: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sunscape Familiar",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Wall")),
                powerToughness =
                    PrintedPowerToughness(power = 0, toughness = SUNSCAPE_FAMILIAR_TOUGHNESS),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val spellCostReductions =
            persistentListOf(
                SpellCostReduction(
                    amount = SUNSCAPE_FAMILIAR_REDUCTION,
                    // CR 202.2: a spell matches on *either* colour, and the engine reads them from the
                    // spell's printed mana cost rather than from the cost actually being paid.
                    spellColors = persistentSetOf(Color.BLUE, Color.GREEN),
                ),
            )
    }
