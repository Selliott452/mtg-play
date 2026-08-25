package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * The event pattern that fires a [TriggeredAbility] (CR 603.2) — the "when/whenever/at" condition,
 * expressed as card-definition data. Additive, flagged core (P5.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the *declaration* of
 * what the ability watches for; `mtg-rules` owns *detecting* that the condition matched a game event
 * (CR 603.3) and *queueing* the ability. Core says "watch for this creature dealing damage"; rules
 * decides, at each transition, whether that happened.
 *
 * Sealed so the rules detector handles every pattern exhaustively and a new pattern breaks
 * compilation rather than going silently undetected. The MVP pool exercises exactly the patterns the
 * four deferred Bogles halves need plus the cast-trigger seam; targeted triggers (CR 603.3d) and
 * intervening-if conditions (CR 603.4) are not needed by any MVP card and are the sealed extension
 * point (P5.2/P6).
 */
sealed interface TriggerCondition {
    /**
     * "When this permanent enters the battlefield" (CR 603.6a) — a self-referential enters-the-
     * battlefield trigger. Cartouche of Solidarity (create a token) and Abundant Growth (draw a
     * card) trigger on their own entry. The trigger carries the entered object as its subject.
     */
    data object EnteredBattlefieldSelf : TriggerCondition

    /**
     * "When this permanent enters **untapped**" (CR 603.6a) — Gingerbread Cabin's "When this land
     * enters untapped, create a Food token". Added by `P-TRIGCOND`.
     *
     * A narrower [EnteredBattlefieldSelf], not an intervening-if condition (CR 603.4). The
     * distinction matters and the CR draws it sharply: the entering permanent's tapped status is part
     * of the *event*, fixed by whatever CR 614.1c replacement applied as it entered, so the condition
     * is evaluated once at that instant. An intervening-if would be re-checked when the ability would
     * resolve (CR 603.4), and tapping the land in response would wrongly stop the token. Making it a
     * condition rather than a check inside the effect is what keeps the trigger from firing at all
     * when the land entered tapped — a trigger that never fires and a trigger that resolves doing
     * nothing are different observable games (the second uses the stack).
     *
     * Paired with [EntersTapped.UnlessYouControl] by construction: a card that prints this condition
     * prints a conditional enters-tapped clause, because a permanent with an unconditional clause
     * would never fire it and one with no clause would always fire it.
     */
    data object EnteredBattlefieldUntappedSelf : TriggerCondition

    /**
     * "When this permanent is put into a graveyard from the battlefield" (CR 603.6b, CR 603.10) — a
     * leaves-the-battlefield trigger. Rancor's "return this to its owner's hand" fires as the Aura
     * arrives in the graveyard (most often via the CR 704.5m fall-off when its creature dies). Per
     * CR 603.10 it is checked against the game state just before the object left; the fired trigger
     * carries, as its subject, the fresh graveyard object (CR 400.7) the ability then acts on.
     */
    data object PutIntoGraveyardFromBattlefieldSelf : TriggerCondition

    /**
     * "Whenever enchanted creature deals damage" (CR 603.2) — the Aura watches the object it is
     * attached to (CR 611.2c) and fires when that creature deals damage, combat or noncombat.
     * Armadillo Cloak's "you gain that much life" fires here; the fired trigger carries the amount
     * of damage dealt (CR 118.9 "that much") and the enchanted creature as its subject. Only combat
     * damage occurs in the MVP pool (the enchanted creatures are vanilla), but the condition is not
     * combat-restricted — a noncombat damage source that is itself an enchanted creature would fire
     * it identically, from the same detection seam (`mtg-rules`).
     */
    data object EnchantedCreatureDealsDamage : TriggerCondition

    /**
     * "Whenever a spell is cast" (CR 603.2, CR 601.2i) — the cast-trigger seam, now filterable
     * (P6.2a; [excludedSpellTypes] added in P6.3). A card carrying this fires as a spell finishes
     * casting (CR 601.2i) **iff** the cast spell matches all three filters:
     * - [spellTypes]: the cast spell's printed card types must include at least one of these
     *   (CR 205.2); an **empty** set matches any spell (the bare "whenever a spell is cast").
     * - [excludedSpellTypes]: the cast spell's printed card types must include **none** of these —
     *   the "noncreature spell" shape (CR 205.2); an **empty** set excludes nothing.
     * - [controlledByYou]: when `true`, the spell's controller must be the trigger source's
     *   controller (CR 603.2e "a spell you control") — control is ownership in the MVP pool; when
     *   `false` any player's cast matches.
     *
     * Guttersnipe's "whenever you cast an instant or sorcery spell" is `SpellCast(spellTypes =
     * {INSTANT, SORCERY}, controlledByYou = true)`; Kessig Flamebreather's "whenever you cast a
     * noncreature spell" is `SpellCast(excludedSpellTypes = {CREATURE}, controlledByYou = true)` —
     * a multi-type spell (an artifact creature) is a creature spell and so is excluded, which is why
     * the exclusion is a type set rather than the complement of [spellTypes]. `mtg-rules` applies all
     * three filters at the `completeCast` detection site; the fired ability's
     * [TriggeredAbility.effect] runs on resolution.
     *
     * @property spellTypes the printed card types that qualify a cast (any one suffices); empty means
     *   any spell.
     * @property excludedSpellTypes the printed card types that disqualify a cast (any one suffices to
     *   disqualify); empty means nothing is excluded.
     * @property controlledByYou whether the cast spell must be controlled by the trigger's controller.
     */
    data class SpellCast(
        val spellTypes: PersistentSet<CardType> = persistentSetOf(),
        val excludedSpellTypes: PersistentSet<CardType> = persistentSetOf(),
        val controlledByYou: Boolean = false,
    ) : TriggerCondition {
        init {
            require(spellTypes.none { it in excludedSpellTypes }) {
                "CR 603.2: a cast trigger cannot both require and exclude a card type, got " +
                    "$spellTypes and $excludedSpellTypes"
            }
        }
    }

    /**
     * "When you draw your [n]th card in a turn" (CR 603.2) — a per-turn draw-count trigger. Sneaky
     * Snacker's "when you draw your third card in a turn, return this card from your graveyard to the
     * battlefield tapped" is [DrewNthCardThisTurn]`(3)`, functioning from [TriggerZoneScope.Graveyard].
     * "You" is the ability's controller — the card's owner in the MVP pool — so the trigger fires only
     * when *that* player's [dev.mtgplay.core.state.PlayerState.drawsThisTurn] reaches exactly [n]
     * (`mtg-rules` detects it at the draw that crosses the threshold, never re-firing on later draws).
     *
     * @property n which draw of the turn fires the ability (Sneaky Snacker's is 3); at least 1.
     */
    data class DrewNthCardThisTurn(
        val n: Int,
    ) : TriggerCondition {
        init {
            require(n >= 1) { "CR 603.2: the draw ordinal a per-turn draw trigger watches is at least 1, was $n" }
        }
    }

    /**
     * Madness's reflexive "when this card is discarded this way, its owner may cast it" ability
     * (CR 702.35b) — the condition of the ability the madness replacement synthesizes on a card it
     * exiles instead of discarding (CR 702.35a). Added in P5.2. Unlike the four battlefield conditions
     * this is never *detected* against a game event: the madness replacement creates the fired trigger
     * directly (functioning from [TriggerZoneScope.Exile]), so the trigger detector never produces it.
     * On resolution the reflexive-cast path offers the owner a yes/no cast for the card's madness cost
     * and, if declined or impossible, puts the card into its owner's graveyard (`mtg-rules`); the
     * ability's [TriggeredAbility.effect] is unused because the may-cast is the engine's, not a
     * [ResolutionEffect].
     */
    data object MadnessCast : TriggerCondition
}
