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
 * *`FW-MODAL`* (docs/design/countering-spells.md §8). A spell can now have **modes** (CR 700.2), and
 * choosing one is a **decision** — the sharper of the two break modes, and the one `4.0.0` set the
 * standard for. [DecisionRequestDto] gains [DecisionRequestDto.ChooseModes] and
 * [DecisionRequestKindDto] gains `CHOOSE_MODES`, so a peer that predates this wave meets the new
 * `choose_modes` discriminator — and the new kind name in its `valueOf` mapping — as a **runtime**
 * decode failure mid-match. It is answerable client→server too: a mode is picked by index like every
 * other [DecisionRequestDto.SingleOptionSelectionDto], which is the explicit call this packet was
 * asked to make.
 *
 * The break reaches the seat view as well, though no [SeatViewDto] field is added: [PendingCastDto]
 * gains a required `chosenModes`, which an older peer's strict codec (`ignoreUnknownKeys = false`)
 * rejects outright — the `4.0.0` break shape.
 *
 * **The new request's *position* is part of the contract, not an implementation detail.** A modal
 * cast surfaces `choose_modes` **before** its `choose_targets` (CR 601.2b precedes CR 601.2c), and the
 * target options the client receives next depend on the mode index it just sent back. A client that
 * cached "the targets of card X" across the two requests would be wrong for exactly the cards this
 * framework adds, since their modes target different *kinds* of object.
 *
 * It is folded into `6.0.0` rather than bumped to `7.0.0` on the premise this file has applied three
 * times now: it lands in the same unreleased wave, so `5.0.0` remains the last version any consumer
 * can have seen, and inflating the major count for a version nobody could have consumed would describe
 * a break that never existed.
 *
 * The version is a single major step even though several frameworks are landing in this wave for the
 * same reason `5.0.0` covered two: `5.0.0` is the last version any consumer can have seen, so one
 * bump carries every break in the wave. `FW-MULTITGT` lands inside that same unreleased wave, so it
 * is folded in rather than bumped to `7.0.0` — parallel packets in this wave may reach the same
 * conclusion, and one shared bump is meant to carry all of them.
 *
 * bump carries every break in the wave.
 *
 * *`FW-BLINK`, `FW-LINKEDEXILE`, `FW-HIDDENCHOICE`, `FW-NONCTRLDEC`* (docs/design/exile-and-return.md).
 * The exile-and-return wave breaks the wire in **both** directions and in three ways, any one of which
 * would be a major bump on the standard the last four versions set. It is absorbed into `6.0.0` rather
 * than bumped to `7.0.0` on this file's own established standard, stated in the `5.0.0` note and applied
 * again above: **`6.0.0` is unreleased.** The only tag is `v0.1.0`, which shipped protocol `1.0.0`, so
 * `1.0.0` — not `6.0.0` — remains the last version any consumer can have seen, and an unshipped major
 * absorbs further breaks from the same wave rather than inflating the major count for a version nobody
 * could have consumed. Naming the breaks is still owed, and they are:
 * 1. [GameObjectDto] gains **required** `linkedExiled` (CR 607.2's linked-ability exile record,
 *    `FW-LINKEDEXILE`) and `reboundTurn` (CR 702.88a's exile marker, `FW-BLINK`). Every game object on
 *    the wire is a [GameObjectDto], so a strict `5.0.0` codec (`ignoreUnknownKeys = false`) rejects
 *    **every seat view**, not only the ones with an exiled card in play — precisely the break shape
 *    `FW-COUNTERS` records directly above, and the fields are required for the same reason: an
 *    omitted-by-default linked-exile record would let an old client watch a Journey to Nowhere leave the
 *    battlefield and never learn which card comes back.
 * 2. [SeatViewDto] gains **required** `pendingHandReveal` (`FW-HIDDENCHOICE`), `pendingOpponentDiscard`
 *    (`FW-NONCTRLDEC`), and `pendingRebound` (`FW-BLINK`), which a `5.0.0` peer's strict codec likewise
 *    rejects outright — the shape `4.0.0` recorded for `pendingLibraryLook`. [CastingPermissionDto]
 *    gains its payload-free `rebound` discriminator alongside them, met as a **runtime** decode failure
 *    inside a priority window's cast options.
 * 3. [DecisionRequestDto] gains [DecisionRequestDto.ChooseRevealedHandCard] (CR 701.16a) and
 *    [DecisionRequestDto.ChooseOpponentDiscards] (CR 701.7a), and [DecisionRequestKindDto] gains
 *    `CHOOSE_REVEALED_HAND_CARD` and `CHOOSE_OPPONENT_DISCARDS`. Their `valueOf` mapping fails at
 *    **runtime** rather than at compile time, so an old peer meets the new `choose_revealed_hand_card` /
 *    `choose_opponent_discards` discriminators and kind names as a decode exception mid-match — the
 *    sharper of the two break modes, and answerable client→server too, since both are decisions an agent
 *    sends an index for.
 *
 * `FW-NONCTRLDEC`'s break is the one worth reading twice, because the wire shape encodes an ADR-007
 * ruling rather than a convenience: `ChooseOpponentDiscards` enumerates the **deciding seat's own hand**
 * and is delivered only to `id.seat`, while `SeatViewDto.pendingOpponentDiscard` is count-only for every
 * seat including that one. The two must not be brought into line — a peer that filled the seat-view
 * record from the request would be publishing a hand the resolving object's controller may not see
 * (CR 402.1). `FW-HIDDENCHOICE` is the deliberate opposite: `pendingHandReveal` carries the revealed
 * cards in full to *both* seats, because CR 701.16a reveals them to every player.
 *
 * ## `7.0.0` — `FW-MANACOST`: a mana ability may cost something
 *
 * A **required-field reshape of the payment plan**, which is the `4.0.0` break shape in its sharpest
 * form: [PaymentPlanDto] is the option payload of `choose_payment_plan`, so a `6.0.0` peer's strict
 * codec rejects **every payment decision** — the single most frequent request in a match — rather than
 * only the ones involving a costed mana ability. It breaks in **both** directions, because a payment
 * plan travels server→client as an option and the chosen index travels back.
 *
 * Three shape changes, all forced by the same fact: a mana ability's activation cost is no longer
 * always "tap this", so *which* ability of a source is being activated, and what it costs, have to be
 * on the wire.
 *
 * 1. [SourceClassKeyDto.profile] changes element type from `List<ManaTypeDto>` — a bare produced
 *    multiset — to [ProductionAlternativeDto], which names the alternative's cost, its production and
 *    its CR 602.5b once-each-turn flag. This is a *type* change on an existing field, so it is not
 *    even decodable as a partial read.
 * 2. [SourceClassKeyDto] **loses** `viaSacrifice`. Sacrificing was one of exactly two costs a mana
 *    ability could have, so it fitted on the class; with Conduit Pylons printing a free `{T}` ability
 *    beside a `{1}, {T}` one, the cost is a per-activation choice and it moved into the alternative.
 * 3. [ManaActivationDto] replaces `produced` with `alternative` and gains a required `costPayment`.
 *    The last is the genuinely new information: with a `{1}` activation cost, *which* mana funded the
 *    activation is a choice the plan has to record, because paying it with green and paying it with
 *    red leave different pools.
 *
 * [ManaAbilityCostDto] is a new sealed hierarchy (`mana`, `tap_self`, `sacrifice_self`,
 * `tap_another_creature`, `put_counter_on_self`). No [DecisionRequestDto] member, no
 * [DecisionRequestKindDto] member and no [TargetDto] member are added: nothing `FW-MANACOST` adds is a
 * new *kind* of decision, only a wider payload for one that exists. The order the activations run in
 * is deliberately **not** on the wire — it is derived from the plan, the pool and the recorded cost
 * payments by both peers (docs/design/mana-payment.md §11.2), so putting it in the message would be a
 * second source of truth for something already determined.
 *
 * The seat view is untouched in shape but not in content: [GameObjectDto] already carries every
 * battlefield object, and objects that have spent a CR 602.5b "Activate only once each turn" mana
 * ability now report it through the existing per-object fields the view already serialises.
 * ### `7.0.0` also carries `FW-NINJUTSU` / `FW-TRIGCOMBAT` / `FW-OPTDRAW`
 *
 * Ninjutsu (CR 702.49) breaks the wire in **both** directions, and it is absorbed into `7.0.0` rather
 * than bumped to `8.0.0` on this file's own repeatedly-applied standard: `7.0.0` is **unreleased**. The
 * only tag is `v0.1.0`, which shipped protocol `1.0.0`, so `1.0.0` remains the last version any consumer
 * can have seen and an unshipped major absorbs further breaks from the same wave. Naming the breaks is
 * still owed:
 *
 * 1. **[PriorityOptionDto] gains [PriorityOptionDto.ActivateNinjutsu]** — the sharper of the two break
 *    modes. A peer meets the new `activate_ninjutsu` discriminator as a **runtime** decode failure
 *    inside a priority window's options, and it is answerable client→server too, since it is an action
 *    an agent sends an index for. This is the first new [PriorityOptionDto] member since `plot_card`.
 *
 *    It is a new member rather than a widened [PriorityOptionDto.ActivateAbility] because ninjutsu is
 *    **synthesized** by the engine from a `Ninjutsu` cost declaration (CR 702.49a's reminder text is the
 *    ability), so it has no index among the source's declared activated abilities — and because it
 *    carries a chosen cost object, the returned attacker, that no other activation option has. Folding
 *    it into `activate_ability` with a sentinel index would have made the wire ambiguous about which
 *    ability an answer activated.
 * 2. **[SeatViewDto] gains required `pendingNinjutsu` and `pendingOptionalDraw`**, which a strict codec
 *    (`ignoreUnknownKeys = false`) on an older peer rejects outright — the break shape `4.0.0` recorded
 *    for `pendingLibraryLook` and `6.0.0` for `pendingRebound`. [PendingNinjutsuDto] and
 *    [PendingOptionalDrawDto] are the new payloads.
 * 3. **No [DecisionRequestDto] member and no [DecisionRequestKindDto] member are added**, which is worth
 *    stating because both frameworks add pauses. Ninjutsu's payment reuses `choose_payment_plan`
 *    unchanged (it is a mana cost like any other), and the optional draw reuses `choose_yes_no` — the
 *    fifth flow to share that request, routed by which `pending*` record is open, exactly as the four
 *    before it. So the milder break mode applies to everything except point 1.
 *
 * `FW-TRIGCOMBAT` adds nothing to the wire at all: a new [dev.mtgplay.core.definition.TriggerCondition]
 * is card-definition data, and definitions are static match configuration that never travels.
 *
 *
 * ## `8.0.0` — the keyword tail: deathtouch, changeling, and a granted evasion
 *
 * **Call: bump to `8.0.0`,** and — unlike the several packets `7.0.0` absorbed — this one is *not*
 * folded into the previous major, because the break is of the sharper kind and hits two independent
 * places at once.
 *
 * 1. **A new required field on [GameObjectDto]**: `dealtDeathtouchDamage` (CR 704.5h). Every game
 *    object on the wire is a [GameObjectDto], so a strict `7.0.0` codec (`ignoreUnknownKeys = false`)
 *    rejects **every seat view**, not only boards containing a deathtoucher. That is the same break
 *    shape `6.0.0` recorded for `linkedExiled`/`reboundTurn`, and the field is required for the same
 *    reason: an omitted-by-default record would let an old client watch a 5/5 die to one point of
 *    damage with nothing in the message explaining why.
 * 2. **Two new vocabulary values, met as a runtime decode failure** rather than a field mismatch.
 *    [dev.mtgplay.core.card.Keyword] gains `DEATHTOUCH` and `CHANGELING` and
 *    [dev.mtgplay.core.card.Evasion] gains `BLOCKABLE_ONLY_BY_HASTE`; both ride in
 *    [PrintedCardDto]'s printed characteristics, whose `parseVocabulary` is deliberately strict, so a
 *    `7.0.0` peer meets the new names as an exception *mid-match* — the harsher of the two break modes
 *    this file distinguishes, and the one `4.0.0` and `5.0.0` were bumped for.
 *
 * The client→server direction is **unchanged**: no [DecisionRequestDto] member, no
 * [DecisionRequestKindDto] value and no [TargetDto] member is added. Deathtouch and the new evasion do
 * change which options an agent is *offered* — a deathtouching trampler's `assign_trample_damage`
 * range is larger (CR 702.2b makes 1 lethal to each blocker), and an attacker with the haste evasion
 * yields fewer `declare_blockers` options — but an option list is a payload the wire already carries,
 * not a new shape.
 *
 * ### Held at `8.0.0` — `W7-C`: filtered looks and the graveyard fold
 *
 * **No bump, because nothing on the wire changes shape** — and the point of saying so here is that this
 * file's standard is to name a break, which means it is also to name the *absence* of one rather than to
 * bump reflexively. The packet adds a fifth `LibraryLookMode`, three `RevealedCardFilter` members, a
 * whole-graveyard exile and a seeded graveyard return. Every one of those is engine-side vocabulary that a
 * peer never sees:
 *
 * 1. **`LibraryLookMode` and `RevealedCardFilter` are not wire types.** Only
 *    [dev.mtgplay.core.definition.LibraryLookSource] crosses, inside `PendingLibraryLookView` — and the new
 *    mode's source is `TOP_OF_LIBRARY`, an existing [LibraryLookSourceDto] value. A filtered look surfaces
 *    the *same* `choose_library_arrangement` request as a scry, with the same fields; it simply enumerates a
 *    different set of options. Under ADR-005 that is the whole difference, and a `4.0.0`-or-later peer
 *    decodes it without knowing the mode exists.
 * 2. **No [DecisionRequestDto] member, no [DecisionRequestKindDto] value, no [TargetDto] member, and no
 *    [SeatViewDto] field.** A whole-graveyard exile and a random return are *effects*, not decisions: the
 *    first is answered by the existing `choose_targets` over a player, and the second is answered by nobody
 *    at all (CR 104.3 — "at random" is the engine's pick, never an enumerated one).
 * 3. **The seeded return is invisible by construction.** It advances the match PRNG, and the PRNG is not on
 *    the wire and never has been: replay is seed-plus-decisions (ADR-006), and a seat view carries the
 *    resulting board rather than the generator that produced it.
 *
 * The one genuinely new *observation* is a [dev.mtgplay.core.event.GameEvent.CardsRevealed] for a filtered
 * look's kept cards, and the event log is not part of [SeatViewDto] at all — the same exclusion the
 * `FW-PREVENT` note records for the damage source.
 *
 * ### Folded into `7.0.0` — `FW-TAPUNTAP`: tap, untap, and choosing your own permanents
 *
 * Two new request members and three new optional seat-view fields, **folded into `7.0.0` rather than
 * bumped to `8.0.0`** on this file's established standard, stated in the `5.0.0` note and applied at
 * every purely-additive packet since: a peer that does not know these members meets them only when a
 * card printing them is in play, and everything else on the wire is unchanged. `7.0.0` is still
 * unreleased when this lands, so no peer has yet pinned it.
 *
 * - [DecisionRequestDto.ChooseAbilityReturn] (`choose_ability_return`), a `SizedSelectionDto` in the
 *   exact shape of `choose_ability_sacrifice`: Quirion Ranger's "Return a Forest you control to its
 *   owner's hand" activation cost. [DecisionRequestKindDto] gains `CHOOSE_ABILITY_RETURN`.
 * - [DecisionRequestDto.ChoosePermanentsToAffect] (`choose_permanents_to_affect`), a
 *   `RangedSelectionDto`: Snap's "Untap up to two lands" and Azorius Chancery's "return a land you
 *   control to its owner's hand", chosen mid-resolution and **untargeted** (CR 609.4).
 *   [DecisionRequestKindDto] gains `CHOOSE_PERMANENTS_TO_AFFECT`.
 * - [SeatViewDto.pendingPermanentSelection] and two new [GameObjectDto] fields —
 *   `activatedAbilitiesActivatedThisTurn` and `skipsNextUntapStep` — all defaulted, so an older
 *   payload still decodes. Every one is public information: the battlefield is a public zone
 *   (CR 400.2), so a selection over it hides nothing, and a "doesn't untap" rider resolves face-up.
 *
 * Worth reading beside `FW-MULTITGT`'s entry: `choose_permanents_to_affect` shares
 * `choose_multiple_targets`' ranged shape and is **not** a targeting request. Its options are not
 * filtered by hexproof or shroud, they are never re-checked for legality, and the answer records no
 * target — a peer that treats the two as interchangeable would be modelling a game these cards do not
 * describe. The absent `cardObjectId` is the visible tell.
 *
 * ### `7.0.0` also carries `FW-X`, `FW-OPTCOST` and `FW-ALTCOST`
 *
 * **Held at `7.0.0` rather than bumped to `8.0.0`**, on this file's own standard, stated in the
 * `5.0.0` note and applied four times since: **`7.0.0` is unreleased.** The only tag is `v0.1.0`,
 * which shipped protocol `1.0.0`, so `1.0.0` — not `7.0.0` — remains the last version any consumer can
 * have seen, and an unshipped major absorbs further breaks from the same wave rather than inflating
 * the major count for a version nobody could have consumed. Naming the breaks is still owed, and they
 * are, in descending order of sharpness:
 *
 * 1. **A new `DecisionRequest` kind** — the sharper of the two break modes.
 *    [DecisionRequestDto] gains [DecisionRequestDto.ChooseXValue] and [DecisionRequestKindDto] gains
 *    `CHOOSE_X_VALUE`, whose `valueOf` mapping fails at **runtime** mid-match rather than at compile
 *    time. It is answerable client→server too, since announcing X is a decision an agent sends an index
 *    for. Note the payload is the announceable **values**, not a count: a peer answers with a position
 *    in that list, and the two coincide on every ordinary board but need not in general.
 *
 *    Kicker deliberately adds **no** kind: it is a `ChooseYesNo`, reusing the request four flows
 *    already share, because "you may pay an additional cost" is exactly the two-answer shape that
 *    request exists for.
 * 2. **Required fields on payloads a peer already decodes.** [PendingCastDto] gains `kicked` and
 *    `chosenX` (the two CR 601.2b announcements); [GameObjectDto] gains `kickedWhenCast` (CR 702.33f's
 *    linked information, public exactly as counters are); [CastingPermissionDto.AlternativeCost] gains
 *    `condition` and `revealsHand`, with [CastConditionDto] a new enum. Every game object on the wire
 *    is a [GameObjectDto], so a strict `6.0.0` codec (`ignoreUnknownKeys = false`) rejects **every
 *    seat view** — the break shape `FW-COUNTERS` recorded, and the field is required for the same
 *    reason: a permanent's kicked-ness changes what its own abilities do.
 * 3. **A widened value inside an unchanged shape.** Mana costs travel as Scryfall brace strings, and
 *    those strings may now contain `{X}` (CR 107.3). No DTO changes, but a peer parsing costs with its
 *    own reader meets a symbol its `6.0.0` grammar rejects. No card in the gauntlet ships with an
 *    `{X}` cost, so this one is latent rather than live — recorded because a peer that hard-codes the
 *    symbol set will meet it the day one does.
 *
 * ### `8.0.0` also carries `W8-E`
 *
 * **Held at `8.0.0` rather than bumped to `9.0.0`**, on this file's own standard, stated in the
 * `5.0.0` note and applied at `7.0.0`: **`8.0.0` is unreleased.** The only tag is `v0.1.0`, which
 * shipped protocol `1.0.0`, so an unshipped major absorbs further breaks from the same wave rather
 * than inflating the major count for a version nobody could have consumed. The breaks are still owed,
 * in descending order of sharpness:
 *
 * 1. **A widened enum a peer decodes by name** — the runtime break mode, in **both** directions.
 *    [AbilityZoneScopeDto] gains `GRAVEYARD` (CR 113.6b — Bramble Wurm's "{2}{G}, Exile this card from
 *    your graveyard"). It travels server→client inside [PriorityOptionDto.ActivateAbility] and
 *    [PendingCastDto], and client→server as part of an activation the agent chose, so an `7.0.0` peer
 *    meets an unknown discriminator mid-match rather than at compile time. It is a *value* widening
 *    inside an unchanged shape, which is the `{X}`-symbol break shape one entry above, except that
 *    this one is live rather than latent: a Bramble Wurm in a graveyard produces the option.
 * 2. **Required fields on payloads a peer already decodes.** [DecisionRequestDto.DeclareBlockers] gains
 *    `minimumBlockers` (a list of the new [BlockerMinimumDto]) and
 *    [DecisionRequestDto.ChooseFromLibrary] gains `optionalSearch`. Both are Kotlin-defaulted, so an
 *    *older* payload still decodes into the new DTO; the break runs the other way, since the strict
 *    codec (`ignoreUnknownKeys = false`) of an older peer rejects a payload that carries them.
 *
 *    Both fields exist for the same ADR-005 reason and neither is cosmetic. `minimumBlockers` is the
 *    only way a deciding seat can see Troll of Khazad-dûm's CR 509.1b "except by three or more
 *    creatures" — the pairing options cannot express it, so a peer that ignores the field will offer
 *    single blocks the engine then rejects. `optionalSearch` is what tells a peer that
 *    `ChooseFromLibrary` has **two** trailing indices rather than one: "fail to find" (CR 701.18b,
 *    shuffles) and "don't search at all" (CR 601.3b, does not). A peer that assumes one would read
 *    Gatecreeper Vine's decline as an out-of-range index.
 *
 * **No `DecisionRequest` kind is added**, which is the sharper break mode this wave avoids: every new
 * decision here is a widening of a request that already exists, and the whole-block-declaration
 * constraint is published as data on `DeclareBlockers` rather than as a new request shape.
 * ### Held at `8.0.0` — `W8-B`: a mana ability's non-mana rider
 *
 * [ProductionAlternativeDto] gains `rider`, a nullable [ManaAbilityRiderDto] carrying the CR 605.1a
 * non-mana half of a mana ability — Elves of Deep Shadow's "This creature deals 1 damage to you".
 *
 * **Held at `8.0.0` rather than bumped to `9.0.0`**, on this file's own repeatedly-applied standard:
 * `8.0.0` is **unreleased**. The only tag is `v0.1.0`, which shipped protocol `1.0.0`, so `1.0.0`
 * remains the last version any consumer can have seen, and an unshipped major absorbs further breaks
 * from the same wave rather than inflating the major count for a version nobody could have consumed.
 *
 * **The break is nonetheless live rather than latent, and it is worth being precise about why.** The
 * field defaults to `null`, but [ProtocolJson] sets `encodeDefaults = true`, so `"rider": null` is
 * written on *every* production alternative in *every* offered payment plan — not only on the plans
 * that carry one. A strict `8.0.0` codec (`ignoreUnknownKeys = false`) therefore rejects every
 * `choose_payment_plan` on any board with a mana source, which is every board. That is the same break
 * shape `FW-COUNTERS` recorded for `GameObjectDto`, and it is folded here for the identical reason.
 *
 * **It has to be on the wire at all** for two independent reasons, either of which alone would settle
 * it. A remote agent choosing between plans must be able to see that one of them costs it life, or the
 * option set it observes is not the option set the engine offered (ADR-005). And the round trip must be
 * lossless: the rider is part of the payment-equivalence key
 * ([dev.mtgplay.rules.decision.SourceClassKey.profile]), so an alternative reconstructed without it
 * would no longer belong to its own source class, and the executor's CR 601.2g membership check would
 * refuse the plan outright rather than quietly running it.
 *
 * `TriggeredAbility.addsMana` — the packet's other core addition, Burning-Tree Emissary's "When this
 * creature enters, add `{R}{G}`" — adds **nothing** to the wire. It is a property of a card definition,
 * and its only observable consequence is mana in a pool, which [SeatViewDto] already carries.
 * ### Held at `8.0.0` — `W8-C`: status-change triggers and a target-conditional cost
 *
 * **No bump, and naming the absence is the point** — the standard this file set at `W7-C` is that a
 * packet owes an entry either way. `W8-C` adds three
 * [dev.mtgplay.core.definition.TriggerCondition] members (a permanent becoming tapped, a permanent being
 * dealt damage, and the `AnyOf` disjunction), a
 * [dev.mtgplay.core.definition.PermanentRestriction] member (`CREATURE_OR_VEHICLE`), a
 * [dev.mtgplay.core.definition.CostReduction] member (`IfTargets`) with its
 * [dev.mtgplay.core.definition.TargetCondition] enum, and three cards. Every one of those is
 * card-definition vocabulary, and definitions are static match configuration that never travels — the
 * same ruling `7.0.0` records for `FW-TRIGCOMBAT`'s trigger condition.
 *
 * Four things a peer might have been expected to see, and why none of them is a shape change:
 *
 * 1. **No [DecisionRequestDto] member, no [DecisionRequestKindDto] value, no [TargetDto] member.**
 *    "Exile two target artifacts" is answered by `choose_multiple_targets`, which `6.0.0` already added;
 *    a fired status-change trigger is ordered through the existing `order_triggers`; and a
 *    target-conditional cost is announced through nothing at all — it is not a decision.
 * 2. **No [SeatViewDto] or [GameObjectDto] field.** Becoming tapped changes [GameObjectDto.tapped], which
 *    has been on the wire since P7.1, and being dealt damage changes its marked damage, likewise. Neither
 *    trigger stores anything of its own.
 * 3. **[DecisionRequestDto.ChoosePaymentPlan] is unchanged**, including its `cost` string. `FW-COST`
 *    added that field precisely so a modified cost could be *read* rather than inferred, and a cost
 *    modified by the spell's chosen target renders identically to one modified by a board count —
 *    `{1}{W}` is `{1}{W}`. The new input is invisible on the wire by construction.
 * 4. **A narrower option list is not a shape change.** `FW-TGTCOND` filters a Ride's End's
 *    `choose_targets` options to the targets its controller can afford, so the same request carries
 *    fewer entries on some boards. `8.0.0`'s own entry already makes this argument for deathtouch and the
 *    haste evasion: an option list is a payload the wire carries, and which options are in it is what
 *    ADR-005 is about, not what the schema is.
 * ## `9.0.0` — `W8-A`: Gates, surveil, and a "you may" that wraps a whole trigger
 *
 * **Call: bump to `9.0.0`.** Three required-field additions and no new request kind — the milder of the
 * two break modes throughout, but three separate instances of the shape `4.0.0` and `6.0.0` were bumped
 * for, and the recorded standard is to name a break rather than argue that nobody is listening.
 *
 * 1. **[LibraryArrangementDto] gains a required `toGraveyard`** — surveil's fourth destination
 *    (CR 701.44a). It is the option payload of every `choose_library_arrangement`, so an `8.0.0` peer's
 *    strict codec (`ignoreUnknownKeys = false`) rejects **every** look decision, not only a surveil's:
 *    Preordain's scry and Ponder's reorder break too. That is the sharpest of the three, and the field is
 *    required rather than defaulted for the reason `FW-COUNTERS` gave: an omitted destination would let
 *    an old client render an arrangement it is silently misreading, and the difference between the bottom
 *    of a library and a graveyard is the whole of the card.
 * 2. **[SeatViewDto] gains a required `pendingOptionalTrigger`** (CR 603.2) — the yes/no pause of a
 *    triggered ability whose whole effect is inside a printed "you may" (Mortuary Mire). The break shape
 *    `4.0.0` recorded for `pendingLibraryLook` and `7.0.0` for `pendingOptionalDraw`.
 * 3. **[PendingColorChoiceDto] gains a required `playedLand`** (CR 614.12, CR 305.1). An as-enters colour
 *    choice can now interrupt the play-land special action as well as a resolving permanent spell — a
 *    land is never cast — and the record says which route it interrupted, because there is no stack entry
 *    to read it off. It rides inside [SeatViewDto], so an `8.0.0` peer rejects any seat view carrying one.
 *
 * **No `DecisionRequest` kind, no [DecisionRequestKindDto] value, and no [TargetDto] member are added**,
 * which is the packet's central wire claim and worth stating because all three of its decisions are new
 * *positions*. A Gate's colour choice reuses `choose_color` unchanged — only its option **list** is
 * shorter, and under ADR-005 a narrower enumeration of an existing request is not a new decision. A
 * surveil reuses `choose_library_arrangement`, exactly as `W7-C`'s filtered look did. And the "you may"
 * reuses `choose_yes_no`, the seventh flow to share that request, routed by which `pending*` record is
 * open. So the client→server direction changes shape nowhere; only what the server sends does.
 *
 * `CardDefinition.untapsInEachOtherPlayersUntapStep` (Bender's Waterskin, CR 613.11) and
 * [dev.mtgplay.core.event.GameEvent.CardSurveilled] add **nothing** to the wire: card definitions are
 * static match configuration that never travels, and the event log is not part of [SeatViewDto] at all —
 * the same exclusion the `FW-PREVENT` note records for the damage source.
 * ### `8.0.0` also carries `W8-G` — artifacts and the awkward singles
 *
 * **Held at `8.0.0` rather than bumped to `9.0.0`**, on this file's own standard and for the reason the
 * `FW-X` entry above states in full: **`8.0.0` is unreleased.** The only tag is `v0.1.0`, which shipped
 * protocol `1.0.0`, so an unshipped major absorbs further breaks from the same wave rather than inflating
 * the major count for a version nobody could have consumed. Naming the breaks is still owed:
 *
 * 1. **A new `DecisionRequest` kind** — the sharper break mode. [DecisionRequestDto] gains
 *    [DecisionRequestDto.ChooseTapOrUntap] (`choose_tap_or_untap`) and [DecisionRequestKindDto] gains
 *    `CHOOSE_TAP_OR_UNTAP`, whose `valueOf` mapping fails at **runtime** mid-match rather than at compile
 *    time. It is answerable client→server, since the three-way decline/tap/untap answer is a decision an
 *    agent sends an index for. [TapOrUntapChoiceDto] is the new payload enum.
 * 2. **Two optional seat-view fields, both defaulted, so an older payload still decodes.**
 *    [SeatViewDto.pendingTapOrUntap] ([PendingTapOrUntapDto]) and, on [PlayerViewDto],
 *    `combatPhasesToSkip`. Both are public information: a mid-resolution choice over a target announced at
 *    CR 603.3d hides nothing, and a CR 500.10 scheduled combat skip resolves face-up.
 *
 * `combatPhasesToSkip` is the one worth a peer's attention rather than a codec's. It is not decoration:
 * an agent that cannot see it will plan attacks in a combat phase that is never going to happen, which is
 * the enumeration blindness ADR-005 exists to prevent. It is defaulted only so the wire stays
 * backward-decodable, not because it is optional to *understand*.
 * ### `9.0.0` — `W8-D`: card advantage and graveyard artifacts
 *
 * **Bumped rather than folded**, unlike the last several packets, and for one specific reason: this wave
 * changes the shape of an **existing** payload rather than only adding members. Every prior break was
 * either a new discriminator (met at runtime, only when a card printing it is in play) or a new required
 * field (met on the first seat view). This one is a field *replacement* on a payload that travels in both
 * directions, which no amount of "the peer has not met that card yet" softens — an `8.0.0` peer and a
 * `9.0.0` peer disagree about a message they both already know how to send.
 *
 * In descending order of sharpness:
 *
 * 1. **A changed field on an existing payload.** [SacrificeRequirementDto] replaces its `subtype: String`
 *    with `filter: SacrificeFilterDto`, because Dread Return's flashback cost is "Sacrifice three
 *    **creatures**" — a card type, which a printed subtype cannot express. It rides inside
 *    [CastingPermissionDto.Flashback] and [CastingPermissionDto.AlternativeCost], which ride inside
 *    [DecisionRequestDto.ChooseAction]'s cast options, so a strict `8.0.0` codec rejects every priority
 *    window that offers Fireblast, Lava Dart, or Dread Return. Both directions: the same permission
 *    travels back inside an answered option.
 * 2. **Three new `DecisionRequest` kinds** — the runtime break mode. [DecisionRequestDto] gains
 *    `choose_optional_mana_payment` (CR 601.3b, Nihil Spellbomb's "you may pay {B}"),
 *    `choose_graveyard_card_to_exile` (CR 701.3a, Relic of Progenitus — answered by the **targeted**
 *    player, whose graveyard is public so the options are unredacted), and `choose_revealed_card_type`
 *    (CR 609.4, Winding Way's resolution-time "choose creature or land"). [DecisionRequestKindDto] gains
 *    the three matching values, whose `valueOf` mapping fails mid-match on an old peer. All three are
 *    answerable client→server.
 *
 *    The optional-mana request deliberately **reuses** [CounterPaymentOptionDto] as its answer payload:
 *    who decides and what a decline costs are facts of the *request*, while "decline, or pay by this
 *    plan" is the same two shapes either way.
 * 3. **A new `CastingPermission` discriminator.** [CastingPermissionDto.Evoke] (`evoke`, CR 702.74a) —
 *    Mulldrifter. Runtime break, met only in a window that offers an evoke cast.
 * 4. **Two new defaulted [GameObjectDto] fields.** `evokedWhenCast` (CR 702.74a's linked information,
 *    public exactly as `kickedWhenCast` is) and `playGrantedTurn` (CR 118.5, the face-up exile marker
 *    Reckless Impulse leaves). Both defaulted, so an older *payload* still decodes here; a strict older
 *    peer still rejects the newer one, which is the asymmetry every `GameObjectDto` addition has had.
 * 5. **A widened enum inside an unchanged shape.** [DecisionRequestDto.ChooseRevealedCardType] carries
 *    [dev.mtgplay.core.definition.RevealedCardFilter] names, and the enum gains `LAND_CARD`. No peer
 *    that predates the request can meet the value, so this is latent by construction.
 *
 * **`PriorityOption.PlayLand` gains a `source`**, defaulted to the hand, so a land may be played from
 * exile (Reckless Impulse). It is *not* a [CastingPermissionDto] change: a land is never cast (CR 305.1),
 * so no casting permission could ever have reached one, which is precisely why the field is on the option
 * rather than on a permission.
 *
 * No new seat-view field is added. The three new pauses are all public — a graveyard is a public zone
 * (CR 400.2) and the other two are the resolving object's controller's — so
 * [DecisionViewDto.Elsewhere]'s kind is the whole of what a non-deciding seat needs, and the
 * count-only mirrors that [SeatViewDto.pendingOpponentDiscard] exists for have nothing to hide here.
 * ### Held at `8.0.0` — `FW-PREVENT2`: the global prevention store
 *
 * **Call: no bump**, on this file's own repeatedly-applied standard — `8.0.0` is **unreleased**. The
 * only tag is `v0.1.0`, which shipped protocol `1.0.0`, so `1.0.0` remains the last version any
 * consumer can have seen and an unshipped major absorbs a further break from the same wave rather than
 * inflating a major count for a version nobody could have consumed. Naming the break is still owed:
 *
 * 1. **A new required field on [SeatViewDto]**: `preventionEffects`, a list of
 *    [TimedPreventionEffectDto] (CR 615). A strict `8.0.0` codec (`ignoreUnknownKeys = false`) rejects
 *    every seat view carrying it, which is exactly the break shape the `timedEffects` addition
 *    recorded in the `6.0.0` note — and the field is required for the same reason: an
 *    omitted-by-default list would let an old client watch a Lightning Bolt deal nothing with nothing
 *    in the message explaining why.
 *
 * The client→server direction is **unchanged**, and that is not an accident of scope. Both cards this
 * framework ships resolve without asking the wire anything new: Flaring Pain makes no choice at all,
 * and Prismatic Strands' "colour of your choice" reuses [DecisionRequestDto.ChooseColor], the request
 * Utopia Sprawl's CR 614.12 as-enters choice already defined. Reusing it is the honest call rather
 * than the cheap one — the payload is identical (five colours in WUBRG order, answered by index) and
 * the two flows differ only in which pending record is open, which is precisely the disambiguation
 * `ChooseYesNo`'s five sharers already rely on. So no [DecisionRequestKindDto] value is added and
 * nothing fails at `valueOf` mid-match.
 *
 * **The option set is otherwise deliberately unchanged**, which is the framework's central claim.
 * Prevention changes an *outcome*, never an option list: a Bolt is still castable at a creature a
 * shield protects, an attack into a shielded board is still declarable, and Flaring Pain adds no line
 * anywhere. An agent that could not see [SeatViewDto.preventionEffects] would still be offered every
 * legal play — it would simply misvalue all of them, which is why the field is carried unfiltered to
 * both seats rather than left off.
 *
 * ### Held at `8.0.0` — `FW-BARGAIN`: the optional additional cost with a chosen object
 *
 * **Call: no bump**, for the reason the entry above gives — `8.0.0` is unreleased, so `1.0.0` remains
 * the last version any consumer can have seen. The breaks, in descending order of sharpness:
 *
 * 1. **A new `DecisionRequest` kind**, the harsher of the two modes. [DecisionRequestDto] gains
 *    [DecisionRequestDto.ChooseOptionalCostSacrifice] and [DecisionRequestKindDto] gains
 *    `CHOOSE_OPTIONAL_COST_SACRIFICE`, whose `valueOf` mapping fails at **runtime** mid-match. It is
 *    answerable client→server too, since paying a bargain is a decision an agent sends indices for.
 *
 *    The *announcement* deliberately adds **no** kind: like kicker's, it is a `ChooseYesNo`, because
 *    "you may pay an additional cost" is exactly the two-answer shape that request exists for. And the
 *    selection is deliberately **not** folded into `choose_sacrifices_for_cost`: a card may print both
 *    a mandatory sacrifice cost and a bargain, so one shared request would leave the wire ambiguous
 *    about which cost an answer paid — the objection `FW-ADDSAC` recorded when it declined the same
 *    reuse.
 * 2. **A required field on a payload every seat view carries.** [GameObjectDto] gains
 *    `optionalCostPaidWhenCast` (CR 702.166b's linked information, public exactly as `kickedWhenCast`
 *    is), and [PendingCastDto] gains `optionalCostTaken` and `optionalCostObjects`. Every game object
 *    on the wire is a [GameObjectDto], so a strict codec rejects **every** seat view, not only boards
 *    containing a bargained permanent — the break shape `FW-COUNTERS` and `FW-OPTCOST` both recorded.
 * 3. **A widened value inside an unchanged shape.** [CastingPermissionDto.Flashback] gains a `tap`
 *    field ([TapRequirementDto]) alongside its `sacrifice`, because CR 702.34c admits more than mana
 *    in a flashback cost. Recorded here rather than under `FW-PREVENT2` because it is the same
 *    permission payload, changed once.
 *
 * ### Held at `9.0.0` — `W9-A`: the entry-turn stamp, the conditional loot, and ward
 *
 * **Call: no bump**, on this file's own repeatedly-applied standard — `9.0.0` is **unreleased**. The
 * only tag is `v0.1.0`, which shipped protocol `1.0.0`, so `1.0.0` remains the last version any
 * consumer can have seen and an unshipped major absorbs a further break rather than inflating a major
 * count for a version nobody could have consumed. Naming the break is still owed, and there is exactly
 * one:
 *
 * 1. **A field on a payload every seat view carries.** [GameObjectDto] gains `enteredTurn` (CR 603.6a —
 *    the turn a permanent entered the battlefield), public for the reason `kickedWhenCast` is: every
 *    seat watched it arrive, and a card asking *"unless this creature entered this turn"* is a question
 *    both players must be able to answer. Every game object on the wire is a [GameObjectDto], so a
 *    strict `8.0.0`-era codec would reject **every** seat view, not only boards containing a ward or a
 *    ninja — the break shape `FW-COUNTERS`, `FW-OPTCOST`, and `FW-BARGAIN` all recorded. It is
 *    defaulted rather than required, so a peer that omits it still decodes.
 *
 * **No new `DecisionRequest` kind, and that is the notable part of a packet that added ward.** Ward's
 * CR 702.21a payment reuses [DecisionRequestDto.ChooseCounterPayment], the request Force Spike's
 * CR 118.3a unless-pay already defined: the payload is identical (decline at index 0, then affordable
 * plans) and the two flows differ only in which object is about to be countered, which the request's
 * existing `card` field names. The conditional-loot clause likewise reuses `ChooseYesNo` and then
 * `ChooseResolutionDiscards` — its whole design was to chain two pauses that already exist rather than
 * mint a third. So [DecisionRequestKindDto] is unchanged and nothing fails at `valueOf` mid-match.
 *
 * **Ward's stack-entry identity does not reach the wire either.** [StackEntry.Ability] and
 * [StackEntry.ActivatedAbilityOnStack] gained an `entryId` so "counter that ability" can name its
 * victim, but [StackEntryViewDto] deliberately does not carry it: it is engine-internal linkage, an
 * agent never sends it, and every option an agent is offered is already an index into an enumerated
 * list. Adding it would widen the wire for something nothing on the far side could use.
 * ### `10.0.0` — `W9-G`: prototype and cascade
 *
 * Two alternate castings, and between them the wire breaks in **three** ways — one of them the first
 * of its kind in this file's history.
 *
 * 1. **A new [CastingPermissionDto] member whose payload is not a cost.** [CastingPermissionDto.Prototype]
 *    carries `cost`, `power` and `toughness` (CR 718.2), because a prototyped spell is a different
 *    *creature*, not merely a cheaper one. Casting permissions travel server→client inside
 *    [PriorityOptionDto] and [PendingCastDto], so a `9.0.0` peer meets the new `prototype` discriminator
 *    as a **runtime** decode failure — the sharper of the two break modes, the one `4.0.0` first
 *    recorded. [CastingPermissionDto.Cascade] adds a second discriminator with the same break shape and
 *    no payload at all, exactly as `rebound` did.
 * 2. **A required-in-practice field on a payload every seat view carries.** [GameObjectDto] gains
 *    `prototyped` (CR 718.3b's linked information, public exactly as `kickedWhenCast` is). It is
 *    defaulted, so a strict codec accepts an old payload — but a peer that *drops* it renders the wrong
 *    creature, since the permanent's power, toughness and colours are read off this flag. That is a
 *    stronger reason to bump than the fields `FW-BARGAIN` recorded, whose absence merely lost a fact
 *    nothing displayed.
 * 3. **A new [SeatViewDto] field**, `pendingCascade` ([PendingCascadeDto]) — the break shape `4.0.0`
 *    recorded for `pendingLibraryLook`. It is the first pending record on the wire that stays open
 *    **across** a nested cast: a peer that sees a `null` `candidateObjectId` beside a non-empty
 *    `exiledObjectIds` is looking at a cascade whose free cast is in progress, not a malformed record.
 *
 * **No `DecisionRequest` kind is added**, which is the pleasant half. Prototype's cast is an ordinary
 * `ChooseAction` option and cascade's free cast is a `ChooseYesNo` — the sixth flow to share that
 * request, told apart by which pending record is open, the disambiguation `5.0.0`'s note already relies
 * on. So nothing fails at `valueOf` mid-match in the client→server direction.
 *
 * **The random bottoming appears on no wire at all.** CR 702.85a's "in a random order" is drawn from the
 * match PRNG as the ability finishes, and the resulting library order is in no seat view and in no
 * event — [dev.mtgplay.core.event.GameEvent.CardsPutOnBottomInRandomOrder] names the cards in the order
 * they were *exiled*, which is public, and never the order they were placed in, which is not.
 * ### Held at `9.0.0` — `W9-C`: dependent targets, storm, and X on activated abilities
 *
 * **Call: no bump**, on this file's own repeatedly-applied standard — `9.0.0` is **unreleased**. The only
 * tag is `v0.1.0`, which shipped protocol `1.0.0`, so `1.0.0` remains the last version any consumer can
 * have seen and an unshipped major absorbs a further break from the same wave rather than inflating a
 * major count for a version nobody could have consumed. Parallel packets in this wave reach the same
 * conclusion, and one shared bump is meant to carry all of them. Naming the breaks is still owed, in
 * descending order of sharpness:
 *
 * 1. **Three new required fields on payloads every seat view carries.** [TurnDto] gains
 *    `spellsCastThisTurn` (CR 601.2i — the number storm reads) and [PlayerViewDto] gains
 *    `landsEnteredThisTurn` (CR 305 — the landfall fact). Every seat view carries a turn and two player
 *    views, so a strict `9.0.0` codec (`ignoreUnknownKeys = false`) rejects **every** seat view, not only
 *    ones from a game containing these cards — the break shape `FW-COUNTERS` and `FW-BARGAIN` both
 *    recorded.
 *
 *    Both are **required rather than defaulted**, and for the same reason: an agent that could not see
 *    them would be offered every legal play and would misvalue several of them. A Weather the Storm in
 *    hand is worth 3 life or 12 depending on `spellsCastThisTurn`, and a Searing Blaze is worth 1 damage
 *    or 3 depending on `landsEnteredThisTurn`. Re-deriving either from the event log is not something the
 *    seat-view contract asks of a client.
 * 2. **A defaulted field on a pause payload.** [PendingActivationDto] gains `chosenX` (CR 601.2b via
 *    CR 602.2b). Defaulted, so an older *payload* still decodes here; a strict older peer still rejects
 *    the newer one, which is the asymmetry every additive field has had. It rides the wire rather than
 *    being reconstructed because on the activation path the announcement is settled **first**, so a paused
 *    activation that dropped it would decode to a gathering stage *before* the target choice rather than
 *    after it — a different pause, not a cosmetic loss.
 *
 * **No new `DecisionRequest` kind, and that is the framework's central wire claim.** All three cards reuse
 * requests that already exist: an activation's value of X is a [DecisionRequestDto.ChooseXValue], the same
 * payload a cast's has always been, and a spell's *second* targeting line is a
 * [DecisionRequestDto.ChooseTargets], the same payload its first one is. So [DecisionRequestKindDto] is
 * untouched and nothing fails at `valueOf` mid-match — the softer of the two break modes, and here it is
 * absent entirely.
 *
 * Reusing `ChooseTargets` for a second targeting line is the honest call rather than the cheap one. The
 * payload is identical — a card, an object id, and a list of legal targets — and which printed instance of
 * the word "target" an answer settles is read from the open [PendingCastDto], exactly as the same request
 * has been disambiguated between a cast, an activation and a trigger placement since `FW-ABILTGT`. Adding
 * an instance index would be a wire break for information the option list already carries; it becomes a
 * field the day a card prints three lines over the same noun.
 *
 * **No [TargetDto] member and no `StackEntryViewDto` field is added.** A copied spell on the stack
 * (CR 707.10a) renders as the spell it is a copy of, which is what it *is* — a copy has the original's
 * copiable values — and the one thing that distinguishes it, that it ceases to exist instead of reaching a
 * graveyard, is not a choice any agent makes.
 */
const val PROTOCOL_VERSION: String = "10.0.0"
