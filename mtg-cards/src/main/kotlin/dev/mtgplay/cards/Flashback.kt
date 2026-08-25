package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ChosenColorEffect
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TapRequirement
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.damageCannotBePreventedThisTurn
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **prevention pair** — the two cards that between them exercise the CR 615 framework
 * harder than anything else in the pool, and the one pair for which encoding either alone would have
 * been a half-truth. `FW-PREVENT2`.
 *
 * | Card | What it puts in the store | What reads it |
 * |---|---|---|
 * | [flaringPain] | CR 615.9 "damage can't be prevented" | every clause of `damageIsPrevented`, which it turns off |
 * | [prismaticStrands] | a CR 615.1 colour shield | the same function's second clause |
 *
 * **They are each other's answer, and that is why they share a file.** Flaring Pain does nothing at
 * all on a board with no prevention effect — it is a blank card against a deck that prevents nothing —
 * and Prismatic Strands is the pool's reason to play it. Encoding a shield with no way to turn it off
 * would have left the engine unable to express the half of the interaction that CR 615.9 exists for;
 * encoding the disabler alone would have made it untestable against anything but protection.
 *
 * Both print **flashback** (CR 702.34), which is what puts them in the same packet as well as the same
 * file, and they print the keyword's two different halves: Flaring Pain's flashback cost is mana, the
 * shape [CastingPermission.Flashback] already carried, and Prismatic Strands' is not mana at all —
 * "Tap an untapped white creature you control" — which is the non-mana cost component
 * [TapRequirement] was added for (CR 702.34c: "a flashback cost may include more than mana").
 *
 * Oracle text below is the repo's own Scryfall snapshot (`mtg-pauper/.../scryfall-mvp.json`), which is
 * the authority; it agreed with the packet brief on both cards.
 */

/** Flaring Pain's printed identity, for the prevention effect it creates (CR 113.7c). */
private val FLARING_PAIN: CardRef = CardRef("Flaring Pain")

/**
 * Flaring Pain — `{1}{R}` Instant. "Damage can't be prevented this turn. Flashback {R}."
 *
 * **CR 615.9, and it is a rule about prevention rather than a prevention effect.** The rule reads: "A
 * prevention effect that can't be applied simply doesn't do anything." So this creates no shield,
 * removes no shield, and races nothing on a timestamp — it puts one fact in
 * [dev.mtgplay.core.state.GameState.preventionEffects], and while that fact is there every prevention
 * effect in the game fails to apply. A Prismatic Strands cast **in response** still resolves and still
 * creates its shield; the shield simply does nothing for the rest of the turn. Modelling this as a
 * purge of the store would produce identical life totals this turn and the wrong answer for that
 * exact line, which is the one line the card is played to beat.
 *
 * **It turns off protection's damage prevention too** (CR 702.16e), which is the corner most likely to
 * be got wrong by treating protection as something other than prevention. With this in force a red
 * creature's combat damage to a blocker with protection from red is dealt in full. Protection's other
 * three letters are untouched — a red spell still cannot *target* that creature (CR 702.16b), a red
 * Aura still falls off (CR 702.16c), and a red creature still cannot block it (CR 702.16f) — because
 * none of those is prevention and this card says nothing about them.
 *
 * **Flashback `{R}`** (CR 702.34) needs nothing new: it is [CastingPermission.Flashback] with a mana
 * cost, the shape Faithless Looting and Lava Dart already use, and the "then exile it" of the reminder
 * text is the permission's own [CastingPermission.exilesOnLeaveStack] (CR 702.34e). What flashback
 * *means* for this card is that a deck holding the disabler can spend it twice in the one turn it
 * matters, which is the card's whole role in a pool where the shield costs `{2}{W}` and can also be
 * cast twice.
 *
 * The spell targets nothing and its resolution is one call: there is no choice to make, which is what
 * distinguishes it from its partner and is why it needs no resolution clause.
 */
val flaringPain: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Flaring Pain",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                damageCannotBePreventedThisTurn(state, FLARING_PAIN, context.source)
            }

        // CR 702.34: flashback for {R}, cast from the graveyard, exiled as it leaves the stack.
        override val castingPermissions =
            listOf(CastingPermission.Flashback(cost = ManaCost.parse("{R}")))
    }

/**
 * Prismatic Strands — `{2}{W}` Instant. "Prevent all damage that sources of the color of your choice
 * would deal this turn. Flashback—Tap an untapped white creature you control."
 *
 * **A CR 615.1 shield, and not protection.** docs/design/protection.md §0 filed this card under
 * protection and then corrected itself; the correction is worth restating where the card lives,
 * because the wrong model is a plausible one. The shield is keyed on the **source's** colour, not on
 * any recipient's characteristics; it covers *every* permanent and *both* players rather than the
 * caster's creatures; and it catches damage from spells and abilities, not only from creatures. All
 * three follow from it being a global effect with no affected object, which is why it goes to
 * [dev.mtgplay.core.state.GameState.preventionEffects] and not through the CR 613 layer system.
 *
 * **The colour is chosen as the spell resolves** (CR 609.4), not as it is cast, and the difference is
 * observable: an opponent responding to this spell does not yet know which colour it will name. So the
 * choice is a mid-resolution decision, which ADR-004 forbids a [ResolutionEffect] from making — hence
 * [ChosenColorEffect.PreventDamageFromChosenColorThisTurn], a resolution clause the engine pauses for.
 * The card's own [resolution] is therefore the identity: everything it does happens in the clause.
 *
 * **Flashback—Tap an untapped white creature you control** (CR 702.34c: a flashback cost may include
 * more than mana). This is a non-mana cost with a *chosen* object, the shape
 * [dev.mtgplay.core.definition.AbilityCost.ReturnPermanentYouControl] took for Quirion Ranger, and it
 * needed [TapRequirement] because no existing filter can say "white": [PermanentFilter] carries
 * subtype, card type and keyword, and [SacrificeFilter] carries card types alone.
 *
 * **The tapped creature may be summoning sick, and getting that wrong would delete the card's best
 * line.** CR 302.6 restricts the `{T}` symbol *in the cost of an activated ability of that permanent*;
 * this is a cost of a **spell**, and the creature is not the source of anything. So a creature that
 * arrived this turn can pay it. `TapRequirement`'s payability check reads only the tapped status, and
 * that is deliberate rather than an omission.
 *
 * Note the mana part of the flashback cost is `{0}`: the printed cost is the tap and nothing else,
 * which is the same shape Lava Dart's `Flashback({0}, sacrifice a Mountain)` already takes.
 */
val prismaticStrands: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Prismatic Strands",
                manaCost = ManaCost.parse("{2}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None

        // CR 609.4: everything this card does is the colour choice, which is the clause below; the
        // ordinary resolution effect the engine runs first has nothing to do.
        override val resolution = ResolutionEffect { state, _ -> state }
        override val chosenColorEffect = ChosenColorEffect.PreventDamageFromChosenColorThisTurn

        // CR 702.34c: the flashback cost is "Tap an untapped white creature you control" and no mana.
        override val castingPermissions =
            listOf(
                CastingPermission.Flashback(
                    cost = ManaCost.parse("{0}"),
                    tap = TapRequirement(count = 1, color = Color.WHITE, cardType = CardType.CREATURE),
                ),
            )
    }
