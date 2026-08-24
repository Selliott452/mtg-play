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
 *
 * **4.0.0 — `FW-LIBLOOK`** (docs/design/library-look.md §10). Cards can now look at a library privately
 * and arrange what they saw, which breaks the wire in **both** directions. Server→client, [SeatViewDto]
 * gains a required `pendingLibraryLook`, which a `3.0.0` peer's strict codec would reject outright. And
 * — unlike `FW-ABILTGT` — this framework *does* add a `DecisionRequest` kind: [DecisionRequestDto] gains
 * [DecisionRequestDto.ChooseLibraryArrangement] and [DecisionRequestKindDto] gains
 * `CHOOSE_LIBRARY_ARRANGEMENT`, whose `valueOf` mapping fails at **runtime** rather than at compile
 * time, so an old peer meets it as a decode exception mid-match. That is the sharper of the two break
 * modes and a strictly larger break than `3.0.0`'s, so the major bump is not a judgement call.
 *
 * **5.0.0 — `FW-COUNTER`** (docs/design/countering-spells.md §10). Spells on the stack can now be
 * targeted and countered, which breaks the wire in **both** directions and in **three** ways, any one of
 * which would be a major bump on the standard the last three versions set.
 * 1. [TargetDto] gains [TargetDto.SpellOnStackTarget]. Targets travel server→client inside
 *    [DecisionRequestDto.ChooseTargets] and [StackEntryViewDto], and a `4.0.0` peer meets the new
 *    `spell_on_stack_target` discriminator as a **runtime** decode failure, not a compile-time one.
 * 2. [DecisionRequestDto] gains [DecisionRequestDto.ChooseCounterPayment] and [DecisionRequestKindDto]
 *    gains `CHOOSE_COUNTER_PAYMENT`, whose `valueOf` mapping likewise fails at runtime mid-match — the
 *    sharper break mode `4.0.0` already recorded, and this time it is answerable in the client→server
 *    direction too, since the fused unless-pay request is a decision an agent sends an index for.
 * 3. [SeatViewDto] gains a required `pendingCounterPayment`, which a `4.0.0` peer's strict codec rejects
 *    outright.
 *
 * There is no smaller honest call available: this is the first framework to add a [dev.mtgplay.core.state.Target]
 * member, and a target is the one payload that appears in a seat view, in a request, and in the event
 * narration at once.
 */
const val PROTOCOL_VERSION: String = "5.0.0"
