package dev.mtgplay.protocol

/**
 * The match-protocol schema version (ADR-008), carried in every [ServerMessage]/[ClientMessage]
 * envelope so a peer can reject a mismatch loudly rather than silently misreading the wire format.
 *
 * The schema is same-repo with the engine (ADR-008 amendment): a new `DecisionRequest` kind is a
 * compile-time break of the exhaustive DTO mapping, so schema versions track engine versions. Bump
 * this whenever the wire shape changes in a way a peer must know about.
 *
 * **2.0.0 — P8.3.** Held at `1.0.0` from P7.1 through P8.2 on the stated reasoning that the
 * protocol was pre-release with no deployed peer that could observe a change. That premise expired
 * at P8.1: `v0.1.0` is a published, consumable artifact, so a peer built against it now exists in
 * principle. P8.3 then makes the first genuinely **incompatible** change to a payload that travels
 * in *both* directions — [PaymentPlanDto] gains a required `activations` list and
 * [SymbolPaymentDto.WithMana] loses its `source` field (docs/design/mana-payment.md) — so an old
 * peer would both misread an offered plan and send back one the strict codec rejects. That is a
 * major bump under semver, and it also absorbs P8.2's unbumped required [SeatViewDto.cards] field:
 * `1.0.0` and `2.0.0` are the only two shapes any consumer can have seen.
 *
 * **3.0.0 — `FW-ABILTGT`** (docs/design/targeted-abilities.md §10). Abilities can now target, which
 * adds four **required** fields to server→client payloads inside [SeatViewDto]:
 * [StackEntryViewDto.TriggeredAbilityOnStack] and [StackEntryViewDto.ActivatedAbilityOnStack] each
 * gain `targets`, [PendingActivationDto] gains `chosenTargets`, and [SeatViewDto] gains
 * `pendingTriggerTargets`. The codec is strict about unknown fields, so a `2.0.0` peer would reject a
 * `3.0.0` seat view outright — the same reasoning that made P8.2's `cards` field a major change in
 * retrospect, applied at the time rather than after the fact.
 *
 * The client→server direction is **unchanged**: this framework deliberately adds no `DecisionRequest`
 * kind, reusing `ChooseTargets` for a cast, an activation, and a trigger placement alike, so
 * [DecisionRequestKindDto] and every request DTO keep their shape. That makes the break narrower than
 * P8.3's — but it is still a break, and the recorded standard is to say so with a major bump rather
 * than to argue that nobody is listening.
 */
const val PROTOCOL_VERSION: String = "3.0.0"
