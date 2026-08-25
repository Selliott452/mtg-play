package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList

/**
 * A "**choose** one of these card types, then reveal the top [count] cards and put **all** of the chosen
 * type into your hand and the rest into your graveyard" clause (CR 701.16, CR 609.4) — Winding Way's
 * *"Choose creature or land. Reveal the top four cards of your library. Put all cards of the chosen type
 * revealed this way into your hand and the rest into your graveyard."* Additive, flagged core (`W8-D`).
 *
 * **The choice is made as the spell resolves, and that is the whole reason this is not a
 * [SpellMode].** CardSelection.kt recorded Winding Way as the card-selection family's last absentee with
 * exactly this diagnosis: `FW-MODAL` has landed and does not carry it, because a modal card's modes are
 * chosen at CR 601.2b — while the spell is being cast, a whole priority round before it resolves.
 * Winding Way prints no "Choose one —" bullet and no "as you cast"; its choice belongs to the resolution
 * (CR 608.2a), which means an opponent responding to Winding Way does *not* know which half it will be,
 * and the caster gets to see the intervening exchange before committing. Locking the choice in at cast
 * time would have deleted that information asymmetry from the action space (ADR-005).
 *
 * **The choice precedes the reveal**, in the printed order: the four cards are revealed *after* the type
 * is named, so the caster chooses blind. Reversing the two would turn a real gamble into a free pick and
 * is the one ordering this clause has to state.
 *
 * **Every matching card goes to the hand — there is no "up to".** That is what makes this a different
 * clause from [LibraryReveal] rather than a mode of it. [LibraryReveal] is Malevolent Rumble's "you *may*
 * put a permanent card … into your hand", so it enumerates a keep-or-not choice per matching card;
 * Winding Way says "put **all** cards of the chosen type", which is mandatory, so there is nothing to
 * enumerate after the type is named. Modelling it as `LibraryReveal(count = 4, toHandCount = 4)` would
 * have offered the caster the option of keeping fewer — an enumerated line the rules forbid, which
 * ADR-005 calls the worse of the two failure modes.
 *
 * **Core/rules split (ADR-009).** This declares which types may be chosen and how deep the reveal goes;
 * `mtg-rules` owns pausing for the choice, revealing (public information, CR 701.16a), and partitioning
 * the revealed cards.
 *
 * @property count how many cards are revealed from the top of the library (Winding Way's four).
 * @property choices the card types the resolution offers, in printed order (Winding Way's creature, then
 *   land). At least two — a one-option "choice" would be a decision with one legal answer, which is a
 *   card that does not print a choice at all.
 */
data class ChosenTypeReveal(
    val count: Int,
    val choices: PersistentList<RevealedCardFilter>,
) {
    init {
        require(count >= 1) { "CR 701.16: a reveal effect reveals at least one card, was $count" }
        require(choices.size >= 2) {
            "CR 609.4: a resolution-time type choice offers at least two types, was $choices"
        }
        require(choices.distinct().size == choices.size) {
            "CR 609.4: a resolution-time type choice offers each type once, was $choices"
        }
    }
}
