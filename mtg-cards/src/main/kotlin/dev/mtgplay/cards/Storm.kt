package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's one **storm** card (CR 702.40), and the reason it is the right first client for a
 * spell-copying primitive rather than the wrong one.
 *
 * Storm needs exactly two things the engine did not have: a game-wide count of the spells cast so far this
 * turn, and the ability to put copies of a spell onto the stack. `Turn.spellsCastThisTurn` is the first,
 * and it is the shape `Turn.landsPlayedThisTurn` already had; `copySpellOnStack` is the second, and
 * `mtg-rules/Storm.kt` owns it. What Weather the Storm conspicuously does **not** need is the hard half of
 * storm — "you may choose new targets for any of the copies" — because its whole effect is "You gain 3
 * life", which targets nothing. The clause is therefore *vacuous* on this card rather than approximated,
 * which is the difference between an exact encoding and a plausible-looking wrong one.
 */

/** The life Weather the Storm gains, per copy and for the original (CR 119.3). */
const val WEATHER_THE_STORM_LIFE: Int = 3

/**
 * Weather the Storm — `{1}{G}` Instant.
 * "You gain 3 life. Storm (When you cast this spell, copy it for each spell cast before it this turn.)"
 *
 * **The card gains `3 × (storm count + 1)` life, and the copies are separate objects, not arithmetic.**
 * Encoding it as a single scaled lifegain would be simpler and would be wrong in a gauntlet holding
 * counterspells: each copy is its own spell on the stack (CR 707.10a), so an opponent may counter one and
 * let the rest through, and every copy resolves through its own priority round. Three life times four is
 * not the same game as four spells each gaining three.
 *
 * **The resolution order is the reverse of the reading order.** Storm is a *cast* trigger (CR 702.40a), so
 * it goes on the stack **above** the Weather the Storm that produced it and resolves **first**; the copies
 * it makes go above the original too. Every copy therefore gains its life before the printed spell does.
 * Nothing in this definition says so — it falls out of where a cast trigger goes — and it is the whole
 * shape of a storm card.
 *
 * **The count is every player's spells, and it is fixed as the trigger fires.** "Each spell cast before it
 * this turn" (CR 702.40a) counts an opponent's cantrips as readily as the caster's own, and the number is
 * settled at CR 601.2i — so an opponent responding to the trigger with three more spells does *not* make
 * the storm larger. Copies themselves are created rather than cast (CR 707.10a) and so never count.
 *
 * **An instant, so it may be cast in an opponent's turn** — which is the deck's actual use for it,
 * answering a burn spell at the end of a turn in which both seats have already emptied their hands.
 *
 * The whole spell is [storm] plus one lifegain; there is no card-local storm machinery, because storm is a
 * keyword the engine implements (`mtg-rules/Storm.kt`) and this card only declares it.
 */
val weatherTheStorm: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Weather the Storm",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED

        // CR 115: the spell targets nothing, which is exactly what makes CR 702.40a's
        // "you may choose new targets for any of the copies" vacuous here (see the file header).
        override val targetSpec = TargetSpec.None
        override val storm = true
        override val resolution =
            ResolutionEffect { state, context ->
                gainLife(state, context.controller, WEATHER_THE_STORM_LIFE)
            }
    }
