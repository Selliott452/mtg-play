package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.OwnerLibraryPlacement
import dev.mtgplay.core.definition.PermanentRestriction
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

/** The generic mana Deem Inferior sheds per card its caster has drawn this turn (CR 601.2f). */
private const val DEEM_INFERIOR_REDUCTION_PER_DRAW: Int = 1

/**
 * Deem Inferior — `{3}{U}` Sorcery. "This spell costs {1} less to cast for each card you've drawn this
 * turn. / The owner of target nonland permanent puts it into their library second from the top or on
 * the bottom."
 *
 * Added by `W9-F`, and the card that opens two slots at once — a **scalar** cost reduction and a
 * **library-position insertion decided by an owner**. Four readings of the printed text are
 * load-bearing:
 *
 * - **The reduction counts an event tally, not a zone.** "Each card you've drawn this turn" is not a
 *   set of objects anywhere: the cards are in a hand, indistinguishable from the ones already there,
 *   and a card drawn and then discarded still counts. That is why it is
 *   [dev.mtgplay.core.definition.CostReduction.PerDrawThisTurn] and not a fourth
 *   [dev.mtgplay.core.definition.CountScope] — see that member for the argument in full. The turn's
 *   own draw step counts, so this is already `{2}{U}` in a first main phase.
 * - **The **owner** chooses the depth, not the caster.** CR 108.3 fixes ownership for the game, and the
 *   printed line names it explicitly. Letting the caster choose would turn a tempo card into a
 *   near-removal spell — bottoming every threat — which is the plausible-looking better card PLAN.md §7
 *   warns about. It is why this needs a clause at all rather than a resolution effect.
 * - **"Second from the top", not "on top".** The one card of insulation is what stops the card from
 *   being a strictly worse bounce spell against a creature the opponent would happily recast, and it is
 *   a *depth* rather than an end of the library — the position no existing library primitive could
 *   express (see [dev.mtgplay.core.definition.LibraryPosition]).
 * - **"Nonland permanent", an exclusion of a card type.** An artifact land is a land and is not a legal
 *   target, even though it is also an artifact: a permanent has every card type printed on it
 *   (CR 205.1a), so the exclusion reads the whole type line.
 *
 * The move is a zone change, not destruction, so indestructible is no answer to it (CR 702.12b) and
 * neither is regeneration; hexproof is, because this targets (CR 702.11).
 */
val deemInferior: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Deem Inferior",
                manaCost = ManaCost.parse("{3}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.NONLAND_PERMANENT)

        // CR 601.2f: read as the total cost is determined, off the caster's own per-turn tally.
        override val costReduction = CostReduction.PerDrawThisTurn(DEEM_INFERIOR_REDUCTION_PER_DRAW)

        // CR 608.2c: the whole effect is the clause, because the depth is a decision and the deciding
        // seat is the permanent's owner rather than this spell's controller (ADR-004).
        override val resolution = ResolutionEffect { state, _ -> state }
        override val ownerLibraryPlacement = OwnerLibraryPlacement
    }
