package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.Ward
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The ward cards (CR 702.21a). One so far — Mono-Blue Terror's [tolarianTerror] — and its own file
 * because ward is the framework `FW-WARD` built and the next card that prints it belongs beside this one
 * rather than wherever its colour lands.
 *
 * The keyword is a **static** ability that grants a triggered one whose text is the mechanic's
 * (CR 702.21a), so a card declares only its cost ([dev.mtgplay.core.definition.CardDefinition.ward]) and
 * `mtg-rules` synthesizes the trigger — the same split ninjutsu and madness already use.
 */

/** The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3). */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Tolarian Terror — `{6}{U}` Creature — Serpent, a 5/5. "This spell costs {1} less to cast for each
 * instant and sorcery card in your graveyard. Ward {2} (Whenever this creature becomes the target of a
 * spell or ability an opponent controls, counter it unless that player pays {2}.)"
 *
 * **Mono-Blue Terror's namesake, and the card the deck is built to cast on turn four.** Its cost half is
 * [crypticSerpent]'s exactly — a CR 601.2f count of instant and sorcery cards in its controller's
 * graveyard, floored at `{U}` (CR 118.7a, a generic reduction never touches a coloured pip) — and needed
 * nothing new. What kept it out of the pool for two waves is its second line.
 *
 * **Ward {2} is what makes the body stick, and every part of that is a rules fact rather than flavour:**
 *
 * - **It triggers on *becoming* a target, not on resolution.** CR 601.2c chooses a spell's targets
 *   *before* CR 601.2g pays for it, so an opponent who tapped out for a removal spell has already
 *   spent everything when ward asks for `{2}` — and watches the spell get countered. That timing is the
 *   card, and encoding ward as a resolution-time effect would delete it.
 * - **"a spell *or ability*"** — an opponent's targeted activated or triggered ability triggers ward too,
 *   and countering one is a different action from countering a spell (CR 113.7a: an ability is not a
 *   card, so it goes nowhere and no object is born). Building only the spell half would have let every
 *   targeted ability in the gauntlet slip past a `{2}` tax the printed line charges.
 * - **"an opponent controls"** — its own controller may target it freely, so the Terror can be given an
 *   Aura or saved by a protection spell without paying its own tax.
 * - **"counter *it*"** — the victim is the object that did the targeting, which ward's trigger captured
 *   when it fired. That reference is *linked information and not a target*, so nothing re-checks it: if
 *   the targeting spell has already resolved or been countered by the time the ward trigger resolves,
 *   ward counters nothing, and correctly asks nobody to pay.
 * - **Paying leaves the spell on the stack, fully intact.** Ward is a tax, not protection: `{2}` more and
 *   the removal resolves. The Terror also has no hexproof and no shroud, so it is a legal target either
 *   way — which is why the trigger has to exist at all.
 *
 * A 5/5 for two mana late is a large body, and ward {2} is what stops the format's one-mana removal from
 * trading up against it. The deck's whole plan is the two halves together.
 */
val tolarianTerror: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Tolarian Terror",
                manaCost = ManaCost.parse("{6}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Serpent")),
                powerToughness = PrintedPowerToughness(power = 5, toughness = 5),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val costReduction =
            CostReduction.PerMatching(
                amountPerMatch = 1,
                scope = CountScope.YOUR_GRAVEYARD,
                // "each instant and sorcery card" is one card matching *either* type, not a card matching
                // both — no card is simultaneously an instant and a sorcery, and reading the "and"
                // conjunctively would make the reduction permanently zero.
                predicate =
                    ObjectPredicate.AnyOf(
                        persistentListOf(
                            ObjectPredicate.HasCardType(CardType.INSTANT),
                            ObjectPredicate.HasCardType(CardType.SORCERY),
                        ),
                    ),
            )

        // CR 702.21a. The cost only; the triggered ability the keyword grants is the engine's, identical
        // wherever ward is printed.
        override val ward = Ward(ManaCost.parse("{2}"))
    }
