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
 * **5.0.0** covers three frameworks that landed in the same wave; none shipped
 * separately, so `4.0.0` is the last version any consumer can have seen and one major
 * bump carries all three breaks.
 *
 * *`FW-COUNTER`* (docs/design/countering-spells.md §10). Spells on the stack can now be
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
 *
 * *`FW-MANA`* (docs/design/mana-payment.md §8). A mana ability can now add more than one
 * mana, and how many is read off the board when it resolves (CR 605.2), so a payment plan has to say
 * *what multiset* each activation adds rather than which single type. Two required fields inside
 * [PaymentPlanDto] change type, in **both** directions — it is an offered option server→client and a
 * chosen one client→server: [SourceClassKeyDto.profile] becomes a list of alternatives
 * (`List<List<ManaTypeDto>>`) and [ManaActivationDto.produced] becomes one alternative
 * (`List<ManaTypeDto>`). A `4.0.0` peer would misread every payment option it is offered and send
 * back a plan the strict codec rejects — the same break shape as `2.0.0`'s, so the same major bump.
 *
 * No `DecisionRequest` kind is added: multi-mana production is a change to what an existing option
 * *says*, not a new decision, which is the whole point of the P8.3 plan shape holding.
 *
 * *`FW-ZONETGT`* (docs/design/graveyard-targeting.md §8). Cards in a graveyard can now be targeted, so
 * [TargetDto] gains [TargetDto.CardInGraveyardTarget]. That is the same break shape `FW-COUNTER` records
 * as its first point — targets travel server→client inside [DecisionRequestDto.ChooseTargets] and
 * [StackEntryViewDto], and a `4.0.0` peer meets the new `card_in_graveyard_target` discriminator as a
 * **runtime** decode failure — and on its own it would be a major bump on the standard the last three
 * versions set. It is folded into `5.0.0` rather than bumped to `6.0.0` because the premise the `5.0.0`
 * note already states still holds: this framework lands in the same unreleased wave, so `4.0.0` remains
 * the last version any consumer can have seen, and inflating the major count for a version nobody could
 * have consumed would describe a break that never existed.
 *
 * No `DecisionRequest` kind is added and no [SeatViewDto] field: a graveyard-card target is answered
 * through the existing `ChooseTargets` request, and the ADR-007 ruling is deliberately that **no** new
 * per-seat filtering is needed, because a graveyard is a public zone (CR 400.2) whose contents
 * [SeatViewDto] already carries for both seats.
 *
 * **6.0.0** covers the wave-4 frameworks; none shipped separately, so `5.0.0`
 * is the last version any consumer can have seen and one major bump carries them all.
 *
 * * `FW-COST`** (docs/design/cost-modification.md §5). A spell's cost can now be modified
 * before it is paid (CR 601.2f), so the cost a payment plan pays is no longer inferable from the card
 * it is being paid for: an affinity Myr Enforcer printed `{7}` is genuinely a `{3}` on a board with
 * four artifacts. [DecisionRequestDto.ChoosePaymentPlan] therefore gains a **required** `cost` string
 * in Scryfall brace syntax, which a `5.0.0` peer's strict codec rejects outright in the server→client
 * direction, and which a `5.0.0` client would fail to supply coming back.
 *
 * It is the smallest break in this file's history and the bump is still major, for the reason `3.0.0`
 * recorded: the standard here is to name a wire break rather than to argue that no peer is listening.
 *
 * **The option set is deliberately unchanged**, and that is the framework's central claim rather than
 * an accident. A [PaymentPlanDto] is a flat list aligned to the expanded symbols of *the cost it pays*;
 * cost modification changes which cost is expanded and introduces no new payment kind, no new source
 * class, and no new choice. So no `DecisionRequest` kind is added, no enumerated index moves, and
 * `{4}` reduced to `{2}` differs from a printed `{2}` in nothing an agent can observe (ADR-005). The
 * upstream brief predicted this request would "change shape"; it does not, and the added field is
 * display and audit only.
 *
 * *`FW-DURATION`* (docs/design/duration.md §13). Spells and abilities can now create
 * continuous effects with a duration (CR 611.2), and a seat must be able to see them: [SeatViewDto]
 * gains a **required** `timedEffects` list of [TimedContinuousEffectDto], which a `5.0.0` peer's
 * strict codec rejects outright. That is the same break shape `4.0.0` recorded for
 * `pendingLibraryLook` and `3.0.0` for `pendingTriggerTargets`, and the recorded standard is to say
 * so with a major bump rather than to argue that nobody is listening.
 *
 * The break is **server→client only**, and the milder of the two modes: no
 * [DecisionRequestDto] member, no [DecisionRequestKindDto] member, and no [TargetDto] member are
 * added, so nothing fails at `valueOf` mid-match. An until-end-of-turn effect is something an agent
 * *observes*, never something it decides — which is exactly why the client→server direction is
 * untouched.
 *
 * *`FW-ADDSAC`* A sacrifice cost with a *chosen* permanent — "As an additional cost to cast
 * this spell, sacrifice an artifact or creature", "{1}, Sacrifice an artifact or creature:" — is a new
 * **decision**, and by the standard `4.0.0` set that is a major bump on its own. Two of them:
 * [DecisionRequestDto] gains [DecisionRequestDto.ChooseSacrificesForCost] and
 * [DecisionRequestDto.ChooseAbilitySacrifice], and [DecisionRequestKindDto] gains
 * `CHOOSE_SACRIFICES_FOR_COST` and `CHOOSE_ABILITY_SACRIFICE`. A `5.0.0` peer meets the new
 * `choose_sacrifices_for_cost` / `choose_ability_sacrifice` discriminators — and the new kind names in
 * its `valueOf` mapping — as a **runtime** decode failure mid-match, the sharper of the two break modes,
 * and it is answerable client→server too since both are decisions an agent sends indices for.
 *
 * There was a smaller-looking call available and it is the wrong one: reusing the existing
 * `choose_sacrifices` for the intrinsic cost. It is a *different* cost with a different filter that can
 * co-occur with the permission-side one on a single cast, so folding them together would make the wire
 * ambiguous about which cost an answer paid. The recorded standard is to take the honest break rather
 * than to argue nobody is listening.
 *
 * The break reaches the seat view too, though no [SeatViewDto] field is added: the gathering records it
 * already carries gain a required field each — [PendingCastDto] gains `additionalSacrifice` and
 * [PendingActivationDto] gains `chosenSacrifice` — which a `5.0.0` peer's strict codec rejects outright,
 * the `4.0.0` break shape.
 *
 * *`FW-COUNTERS`* — *`FW-COUNTERS`*. Permanents can now carry counters (CR 122), and **counters on a
 * permanent are public information** (ADR-007): a `+1/+1` counter is visible across the table to
 * everyone, exactly like the tapped status and marked damage. So they ride in the seat view
 * unredacted, and they ride there as a **required** field:
 *
 * 1. [GameObjectDto] gains a required `counters: List<CounterDto>`. Every game object on the wire is
 *    a [GameObjectDto] — the battlefield, exile, and the viewer's own hand all carry them — so a
 *    `5.0.0` peer's strict codec (`ignoreUnknownKeys = false`) rejects **every seat view**, not just
 *    the ones with a counter on the board. That is the same break shape `5.0.0` recorded for
 *    `SeatViewDto.pendingCounterPayment`, and it is why the field is not made optional to dodge the
 *    bump: an omitted-by-default field would let an old client render a board it is silently
 *    misreading, and a permanent's counters change what its power and toughness *are*.
 * 2. [CounterDto] is a new sealed hierarchy with the `power_toughness` and `keyword` discriminators.
 *
 * No `DecisionRequest` kind is added: nothing about a counter is a decision. Nothing in the
 * client→server direction changes shape either — this is a one-directional break, the smaller of the
 * two modes, but a break in every message that carries a game object.
 *
 * *`FW-PREVENT` / `FW-PROTECT`* — damage prevention (CR 615), the damage source (CR 120.1), and
 * protection (CR 702.16). Two of the three are **not** wire-visible, and saying which is which is the
 * point of this paragraph:
 *
 * 1. **The damage source is not on the wire.** [dev.mtgplay.core.event.GameEvent.DamageDealt] gained
 *    a `source`, and [dev.mtgplay.core.event.GameEvent] gained a `DamagePrevented` member — but the
 *    event log is not part of [SeatViewDto] at all. It is derived observability (ADR-006), excluded
 *    from the seat view by design and excluded from the replay fingerprint for the same reason. So
 *    the single largest change in this wave, measured in call sites, is invisible to a peer.
 * 2. **Protection is on the wire, and it is a break.** [PrintedCharacteristicsDto] gains a required
 *    `protections: List<QualityDto>`, and [QualityDto] is a new sealed hierarchy with the `color`
 *    and `monocolored` discriminators. Every printed card on the wire carries the field, so a
 *    `5.0.0` peer's strict codec (`ignoreUnknownKeys = false`) rejects every seat view that names a
 *    card — the same break shape `FW-COUNTERS` recorded for [GameObjectDto]. It is not made optional
 *    to dodge the bump for the same reason counters were not: protection changes what a permanent
 *    can be targeted by, blocked by, and damaged by, so a peer silently missing it is a peer
 *    rendering an illegal action space.
 * 3. **No decision shape changes.** Prevention and protection are things an agent *observes* and
 *    that shrink its enumerated options; neither is ever something it decides. No
 *    [DecisionRequestDto] member, no [DecisionRequestKindDto] member, no [TargetDto] member — so the
 *    client→server direction is untouched, the milder of the two break modes.
 *
 * *`FW-MULTITGT`* (docs/design/multi-target.md §9). A spell or ability can now demand more than one
 * target, which **adds a `DecisionRequest` kind** — the sharper of the two break modes, and one
 * `4.0.0` already recorded as a major bump on its own. [DecisionRequestDto] gains
 * [DecisionRequestDto.ChooseMultipleTargets] and [DecisionRequestKindDto] gains
 * `CHOOSE_MULTIPLE_TARGETS`, whose `valueOf` mapping fails at **runtime** mid-match rather than at
 * compile time, and it is answerable in the client→server direction too, since a multi-target choice
 * is a decision an agent sends indices for.
 *
 * `ChooseTargets` is **deliberately left alone**, and that is the framework's central wire claim: a
 * one-target line still surfaces the same request, answered by the same `SingleSelect`, carrying the
 * same fields. Widening it to a ranged shape would have been a break in every existing decision log
 * and every existing target payload for a cardinality no card printed — so the new arity is a new
 * kind, and a `5.0.0` peer meets the old one unchanged.
 *
 * No [TargetDto] member and no [SeatViewDto] field are added: a multi-target choice names the same
 * targets a single one does, and the ADR-007 answer is unchanged because the *zone* still decides
 * visibility (the `FW-ZONETGT` ruling), not the number of objects named.
 *
 * The version is a single major step even though several frameworks are landing in this wave for the
 * same reason `5.0.0` covered two: `5.0.0` is the last version any consumer can have seen, so one
 * bump carries every break in the wave. `FW-MULTITGT` lands inside that same unreleased wave, so it
 * is folded in rather than bumped to `7.0.0` — parallel packets in this wave may reach the same
 * conclusion, and one shared bump is meant to carry all of them.
 */
const val PROTOCOL_VERSION: String = "6.0.0"
