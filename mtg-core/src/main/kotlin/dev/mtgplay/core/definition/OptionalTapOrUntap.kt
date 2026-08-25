package dev.mtgplay.core.definition

/**
 * A "you may tap **or** untap [the target]" clause, resolved as the object resolves (CR 701.20a,
 * CR 701.21a) — Sewer-veillance Cam's *"When this artifact enters or leaves the battlefield, you may tap
 * or untap target creature."* Additive, flagged core (`W8-G`).
 *
 * **This is not modality, and the correction is the whole reason the clause exists.** `FW-TAPUNTAP`
 * dropped Sewer-veillance Cam recording that "the resolution is a *mode* choice on a triggered ability —
 * decline, tap, or untap — and modal resolution exists only for spells". CR 700.2 says otherwise: *"A
 * spell or ability is modal if it has two or more options in a bulleted list preceded by instructions
 * for a player to choose a number of them"*. The Cam prints no bulleted list and no "choose one"; it
 * prints one sentence with a conjunction, exactly as Twiddle does, and the CR 601.2b mode announcement
 * never happens for it. The choice is made **as the ability resolves** (CR 608.2c) and there is nothing
 * to announce when the trigger is put on the stack.
 *
 * That distinction is observable, not pedantic. A mode is chosen when the object goes on the stack, so
 * an opponent responding to a modal spell already knows which mode they are answering; the Cam's
 * opponent does not, and a Cam controller who has not decided yet may watch the response and *then*
 * decide. Encoding it as a mode would move the decision a full priority round earlier and hand a
 * training agent information the card does not give it.
 *
 * **Three outcomes, not two**, and none of them may be dropped: decline, tap, untap. Tapping and
 * untapping are opposite instructions on the same target, and "you may" makes doing nothing a real
 * choice — untapping an already-untapped creature and declining are the same board, but declining an
 * *un*tapped creature and tapping it are not. Encoding the clause as tap-only or untap-only would be a
 * plausible-looking wrong card (PLAN.md §7), which is precisely what the earlier packet refused to ship.
 *
 * **A `data object`, because the clause has nothing to vary.** The mandatory sibling ("Tap or untap
 * target permanent", printed without "you may") would make this a class with an `optional` flag on the
 * day the pool prints one, on the rule [TargetSpec]'s KDoc states for adding a count parameter. It does
 * not print one.
 *
 * **Core/rules split (ADR-009).** This declares only that the resolving object offers the choice;
 * `mtg-rules` owns enumerating the three answers (ADR-005), pausing for them (ADR-004 — a
 * [ResolutionEffect] may not call back into a player), and performing the tap or the untap through the
 * published CR 701.20a/701.21a primitives.
 *
 * **It reads the resolving object's target**, which is what makes it a clause rather than a
 * [PermanentSelection]: the creature was chosen as the ability was put on the stack (CR 603.3d), it is
 * subject to hexproof and the CR 608.2b re-check like any other target, and by the time this clause runs
 * it is already fixed. A clause on an object that targeted nothing — a Cam that entered with no creature
 * on the battlefield — does nothing at all, exactly as CR 608.2c prescribes for an instruction with no
 * object to carry it out on.
 */
data object OptionalTapOrUntap

/**
 * One answer to an [OptionalTapOrUntap] clause (CR 608.2c). A closed enum for [PermanentSelectionAction]'s
 * reason: the rules-side performer handles every member exhaustively, and a fourth answer must break
 * compilation rather than fall through.
 *
 * The order is the printed order with the opt-out first, which is the convention every other declinable
 * enumerated choice in the engine follows — index 0 is always "do nothing".
 */
enum class TapOrUntapChoice {
    /** The "may" is declined; the target is neither tapped nor untapped (CR 608.2c). */
    DECLINE,

    /** The target is tapped (CR 701.20a); a permanent already tapped is unaffected. */
    TAP,

    /** The target is untapped (CR 701.21a); a permanent already untapped is unaffected. */
    UNTAP,
}
