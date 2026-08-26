package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ClauseCondition
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetCondition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.dealDamageThenExileIfItWouldDie
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.effect.hadLandEnterThisTurn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The `W8-C` burn-and-removal packet (docs/decklists.md): the gauntlet's answers that exile an artifact,
 * exile a creature, and turn a creature into a liability. Three of the packet's seven cards are here;
 * the other four are diagnosed at the bottom of this comment and deliberately absent.
 *
 * **[dustToDust]** needed nothing new — `FW-MULTITGT` shipped the count and the removal packet shipped
 * the exile — and is the pool's second card with an *exact* count above one after Ghostly Flicker, so it
 * is the second whose targeting **minimum** decides castability: with one artifact on the battlefield it
 * is simply not an option (CR 601.2c).
 *
 * **[cryoshatter]** is the card `Removal.kt` and `MvpCards.kt` have both recorded as blocked since the
 * removal packet: "nothing in the engine watches a permanent *becoming tapped* or *being dealt* damage".
 * Both are now [TriggerCondition] members, and with them a third — [TriggerCondition.AnyOf], the
 * "When X **or** Y" shape, so one printed ability stays one declared ability. The tap half is announced
 * from a single home ([dev.mtgplay.rules.engine.announceBecameTapped]) reached by all four ways a
 * permanent becomes tapped, because the site that matters most — declaring an attacker — is the one
 * furthest from the others and the easiest to lose silently (triage T18's lesson, applied before the
 * fact).
 *
 * **[ridesEnd]** is the `FW-COST` packet's recorded `FW-TGTCOND` gap: a spell that "prices off its chosen
 * target while cast legality is decided before targets exist". Both halves of that sentence are now
 * answered — the castability gate prices the cheapest target choice available, and the target request is
 * then narrowed to the choices the seat can actually pay for — so a two-mana Ride's End is offered
 * exactly when a tapped permanent is on the board and never offered as a line that dead-ends mid-cast.
 *
 * ## `W9-C` reopened two of the drops, and added a third
 *
 * **[searingBlaze]** was the packet's twice-blocked card and is now encoded. Both blockers were real and
 * both are closed by one framework: [dev.mtgplay.core.definition.SpellDefinition.additionalTargetSpecs]
 * gives a spell a *list* of targeting lines (the shape `TargetCount`'s KDoc and
 * docs/design/multi-target.md §8 both named), and [dev.mtgplay.core.definition.TargetContext] lets a
 * later line read the answers already given, which is what "that player" needs. Landfall — the third,
 * smaller gap — is [dev.mtgplay.core.state.PlayerState.landsEnteredThisTurn], counted at the single
 * battlefield-entry site and read on **resolution** (CR 608.2), not at cast.
 *
 * **Gorilla Shaman** is encoded in `AbilityX.kt`, not here: its blocker was the activation path's missing
 * CR 601.2b announcement of X and the ordering that announcement forces, which is a framework rather than
 * a burn card. The note this file carried was accurate except for the cost, which the oracle text gives as
 * `{X}{X}{1}` rather than `{X}{X}`.
 *
 * **[kaerveksTorch]** is *not* encoded, and its diagnosis has changed rather than merely persisted — see
 * the drop note below, which supersedes `FW-X`'s.
 *
 * ## Torch the Tower, and the four gaps that are now three fewer
 *
 * `W8-C` recorded four gaps against [torchTheTower] and named the third as the real one. Three of the
 * four closed without this packet writing a line:
 *
 * 1. **Bargain** is [dev.mtgplay.core.definition.OptionalAdditionalCost.Bargain], shipped by `FW-BARGAIN`
 *    in wave 8 for Troublemaker Ouphe. The recorded gap — "kicker is the optional-additional shape and is
 *    a bare [ManaCost]; [dev.mtgplay.core.definition.AdditionalCost.Sacrifice] is the sacrifice shape and
 *    is mandatory; neither is the other" — is the exact argument that type's KDoc now makes for existing.
 * 2. **"Or token"** is closed with it: the keyword's union is the engine's, tested as
 *    `definitions[card] is TokenDefinition` rather than by widening a card-type filter.
 * 4. **The scry being conditional** is [dev.mtgplay.core.definition.ClauseCondition], added here — a gate
 *    beside the clause rather than a clause of its own, three lines of engine.
 *
 * 3. **The delayed replacement was the real one**, and it is the whole framework this packet adds. See
 *    [dev.mtgplay.core.state.TimedDeathReplacement] for the store,
 *    `dev.mtgplay.rules.engine.replaceBattlefieldDeath` for the four interception points, and
 *    [dev.mtgplay.rules.effect.exileInsteadOfDyingThisTurn] for the primitive a card composes.
 *
 * ## Dropped, with what each needs
 *
 * - **Kaervek's Torch** `{X}{R}` — "As long as Kaervek's Torch is on the stack, spells that target it
 *   cost {2} more to cast. Kaervek's Torch deals X damage to any target." Its damage line is trivial now
 *   that `FW-X` has landed; the tax is what keeps it out, and the reason is **not** the one `FW-COST` and
 *   `W8-C` recorded. That diagnosis said target enumeration would have to consult affordability, and
 *   `FW-TGTCOND` has since made it do exactly that — [affordableTargetOptions] already drops a target
 *   whose resulting total cost the caster cannot pay, which is precisely the shape a counterspell facing
 *   the Torch needs. The **filter** half therefore runs the right way.
 *
 *   The **gate** half runs the wrong way, and it is the half that matters. `cheapestTargetsFor` prices a
 *   cast's legality at *no targets at all* for every card without a target-conditional reduction, which is
 *   the safe direction for a reduction — pricing without the discount can only over-charge — and the
 *   unsafe one for an increase: with Kaervek's Torch the only spell on the stack, `castIsLegal` would
 *   admit a Counterspell at `{U}{U}`, the filter would then remove its only option, and `targetRequest`
 *   refuses an empty option list in its `init`. That is a crash rather than a missing line. Making it
 *   correct means pricing the gate at the *minimum over legal target choices* — a payment enumeration per
 *   candidate target on every cast in the priority window — which is a change to the legality path of
 *   every card in the pool for one card, the same trade `W9-C` refused for Gorilla Shaman's option (a).
 *
 *   Two smaller gaps remain either way. There is **no declaration for a cost increase** at all
 *   (docs/design/cost-modification.md §3 populates the slot with nothing on purpose), and the one needed
 *   here is a shape no existing modifier has: a static ability of an object **on the stack**, taxing
 *   *another* player's spell, keyed on that spell's chosen targets. Encoding the Torch without the tax
 *   would delete the reason the card is played.
 *
 * - **Torch the Tower** `{R}` — four gaps, and the third is the real one. (1) **Bargain** (CR 701.53) is
 *   an *optional additional* cost that sacrifices a permanent;
 *   [dev.mtgplay.core.definition.SpellDefinition.kicker] is the optional-additional shape and is a bare
 *   [ManaCost], while [dev.mtgplay.core.definition.AdditionalCost.Sacrifice] is the sacrifice shape and
 *   is mandatory. Neither is the other. (2) Bargain may sacrifice "an artifact, enchantment, **or
 *   token**", and [dev.mtgplay.core.definition.SacrificeFilter] carries card types alone — token-ness is
 *   not a card type (CR 111.1) and no filter axis expresses it. (3) "If a permanent dealt damage by this
 *   would die this turn, **exile it instead**" is a *delayed* replacement effect (CR 614, CR 603.7): it
 *   watches a dies event for the rest of the turn, applies to a set of permanents this spell damaged, and
 *   replaces the CR 704.5g move. [dev.mtgplay.core.definition.ReplacementEffect] has exactly two members,
 *   both about the *card's own* zone change, and the framework has no notion of a replacement created by
 *   a resolution, scoped to a duration, or keyed on other objects. (4) The scry is conditional on having
 *   bargained, and [dev.mtgplay.core.definition.ResolutionClauses] carries clauses unconditionally.
 *   Encoding the card without (3) would delete the reason it is played, which is what the drop rule
 *   forbids.
 *
 * - **Gorilla Shaman** `{R}` — "`{X}{X}{1}`: Destroy target noncreature artifact with mana value X."
 *   `FW-X` landed for **spells** only: [dev.mtgplay.core.state.PendingActivation] has no `chosenX`, no
 *   activation surfaces a `ChooseXValue`, and `announcesX`/`xValueOptions` are reachable only from the
 *   cast pipeline. Adding that is mechanical. What is not is the ordering: the target restriction is a
 *   function of the announced X, so X must be announced **before** targets (CR 601.2b before CR 601.2c) —
 *   and `PendingCastRequest.kt`'s header records that this engine deliberately settles both cost
 *   announcements *after* the target stage, so their affordability bound can use the exact reservation.
 *   That header names the card that would force the order back ("a card printing 'X target creatures'");
 *   Gorilla Shaman is that card, and it must take the weaker reservation with it. A third piece: a
 *   restriction that reads a *number chosen this activation* is a shape
 *   [PermanentRestriction] cannot express at all, since it is a closed enum of board questions.
 *
 * - **Cleansing Wildfire** `{1}{R}` — "Destroy target land. Its controller **may** search their library
 *   for a basic land card, put it onto the battlefield tapped, then shuffle. Draw a card." Three gaps in
 *   the CR 701.18 search clause. (1) **The decider is not the resolving spell's controller** — it is the
 *   target's controller. `orchestrateLibrarySearch` takes the decider from `entry.resolutionController`
 *   with no axis to say otherwise; [dev.mtgplay.core.definition.EachOpponentDiscards] is the precedent
 *   that a non-controller clause is expressible, and the search clause has no equivalent. (2) The search
 *   is **optional in a way that changes the shuffle**: the engine's search always shuffles (right for a
 *   mandatory "search … then shuffle" with CR 701.18b failure-to-find), but declining a "may search"
 *   means no shuffle happens at all — and a shuffle is not cosmetic here, it consumes seeded entropy
 *   (ADR-006) and reorders the library. (3) **The draw comes after the search**, and
 *   [dev.mtgplay.core.definition.ResolutionClauses] clauses run *after* the ordinary resolution effect
 *   with nothing after them. Folding the draw into the effect would draw before the shuffle, which is a
 *   different card off a different library. `FW-CLAUSEHOOK` shipped a *post*-resolution hook; this needs
 *   a mid-resolution one.
 */

/** Searing Blaze's damage to each of its two targets without landfall (CR 119.3). */
const val SEARING_BLAZE_DAMAGE: Int = 1

/** Searing Blaze's damage to each of its two targets with landfall (CR 702.135a). */
const val SEARING_BLAZE_LANDFALL_DAMAGE: Int = 3

/** The artifacts Dust to Dust exiles (CR 115.1). */
private const val DUST_TO_DUST_TARGETS: Int = 2

/** Cryoshatter's layer-7c power modifier (CR 613.3, sublayer 7c). */
private const val CRYOSHATTER_POWER_MOD: Int = -5

/** The damage Torch the Tower deals when it was not bargained (CR 120). */
private const val TORCH_DAMAGE: Int = 2

/** The damage a **bargained** Torch the Tower deals instead (CR 702.166b). */
private const val TORCH_BARGAINED_DAMAGE: Int = 3

/** Torch the Tower's scry, on the bargained branch only (CR 701.17a). */
private const val TORCH_SCRY: Int = 1

/** The generic mana Ride's End sheds when it targets a tapped permanent (CR 601.2f). */
private const val RIDES_END_REDUCTION: Int = 3

/**
 * Dust to Dust — `{1}{W}{W}` Sorcery. "Exile two target artifacts." The Bogles and UWX sideboards' answer
 * to Grixis Affinity, and a two-for-one that beats indestructible: exiling is not destroying (CR 701.3a),
 * so a Bridge land's CR 702.12b indestructible does nothing about it.
 *
 * **"Two target artifacts" is [PermanentRestriction.ARTIFACT] with [TargetCount.Exactly]`(2)`** — the same
 * noun Smash to Smithereens and Ancient Grudge already use, with a different cardinality, which is the
 * whole reason `FW-MULTITGT` split a spec into a noun and a count. The card needed no new engine mechanism
 * at all.
 *
 * **The minimum is what makes it a real card rather than a flexible one.** CR 601.2c demands both targets
 * be chosen, so Dust to Dust is **not castable at all** against a board with one artifact — it is absent
 * from the priority window rather than castable for partial value. That is the second printing of an exact
 * count above one in the pool (Ghostly Flicker is the first) and it is the half of multi-targeting that an
 * "up to N" card never exercises.
 *
 * **Both exiles happen, or the spell does not resolve at all.** If one target becomes illegal in response
 * the spell still resolves and exiles the other (CR 608.2b does what it can); if *both* do, it does not
 * resolve. The two artifacts may belong to either player and to the same player — CR 601.2c only forbids
 * naming the same object twice.
 */
val dustToDust: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Dust to Dust",
                manaCost = ManaCost.parse("{1}{W}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec =
            TargetSpec.TargetPermanent(
                restriction = PermanentRestriction.ARTIFACT,
                count = TargetCount.Exactly(DUST_TO_DUST_TARGETS),
            )
        override val resolution =
            ResolutionEffect { state, context ->
                targetedPermanents(context.targets, "Dust to Dust").fold(state, ::exilePermanent)
            }
    }

/**
 * Cryoshatter — `{U}` Enchantment — Aura. "Enchant creature. Enchanted creature gets -5/-0. When enchanted
 * creature becomes tapped or is dealt damage, destroy it."
 *
 * A one-mana blue answer to anything that has to attack or block, and the card `Removal.kt` has named as
 * absent since the removal packet. The static half is an ordinary layer-7c modifier (CR 613.3 sublayer 7c),
 * [Magnitude.Fixed]`(-5)` on power alone — which by itself already blanks most of what it lands on, and is
 * why the Aura is a fine play even when the trigger never fires.
 *
 * **The triggered half is one ability with two event patterns**, and it is declared that way:
 * [TriggerCondition.AnyOf] of [TriggerCondition.EnchantedPermanentBecomesTapped] and
 * [TriggerCondition.EnchantedPermanentIsDealtDamage]. Splitting it into two abilities would produce the
 * same game for every event this pool can generate — nothing taps and damages in one event — and is still
 * not what the card prints; see [TriggerCondition.AnyOf] for the full argument.
 *
 * **"Becomes tapped" is why this is removal and not a trick.** A Cryoshattered creature dies the moment it
 * is declared as an attacker (CR 508.1f), taps for a mana ability, or pays a `{T}` cost — so it is an
 * answer to a Bogles hexproof threat that the Aura can be attached to before it ever attacks. It does *not*
 * fire on a creature that entered the battlefield tapped, which never became tapped (CR 701.20a).
 *
 * **"Is dealt damage" is the recipient side**, distinct from Armadillo Cloak's
 * [TriggerCondition.EnchantedCreatureDealsDamage]: a blocked Cryoshattered attacker fires *both* Auras'
 * triggers if it wears both, once each, for the two directions of the same combat damage.
 *
 * **The destroy is defensive about its subject, and that is CR 608.2 rather than caution.** The trigger
 * carries the enchanted creature as last-known information (CR 603.10), and by the time it resolves that
 * creature may be gone — killed by the same combat damage that fired it (CR 704.5g runs before the trigger
 * is ever put on the stack), bounced, or exiled in response. An instruction that cannot be carried out is
 * simply ignored, so the ability resolves and does nothing rather than failing loudly.
 *
 * **The Aura is not consulted at resolution.** "Destroy it" names the creature the trigger fired for, not
 * "the enchanted creature now": destroying the Aura in response does not save the creature, which is the
 * printed card. Indestructible (CR 702.12b) does save it — [destroy] honours it, and Cryoshatter prints no
 * clause against it.
 */
val cryoshatter: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Cryoshatter",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val staticContinuousEffects =
            persistentListOf(StaticContinuousEffect(powerMod = Magnitude.Fixed(CRYOSHATTER_POWER_MOD)))
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    // CR 603.2: one printed ability, two event patterns.
                    condition =
                        TriggerCondition.AnyOf(
                            persistentListOf(
                                TriggerCondition.EnchantedPermanentBecomesTapped,
                                TriggerCondition.EnchantedPermanentIsDealtDamage,
                            ),
                        ),
                    effect = ResolutionEffect { state, context -> destroyTriggerSubject(state, context.subject) },
                ),
            )
    }

/**
 * Ride's End — `{4}{W}` Instant. "This spell costs `{3}` less to cast if it targets a tapped permanent.
 * Exile target creature or Vehicle."
 *
 * White's catch-all answer, priced by the board: two mana against anything tapped — an attacker, a blocker
 * that has already been declared, a creature that tapped for mana — and five against anything else. Exile
 * rather than destroy, so it beats indestructible and regeneration alike (CR 701.3a), and it is the only
 * removal in the pool whose targeting line reaches an **uncrewed Vehicle**, which is not a creature at all.
 *
 * **The cost reduction is [CostReduction.IfTargets], the pool's first cost input that reads a *choice*
 * rather than the board** (`FW-TGTCOND`). `MvpCards.kt` has recorded this card as blocked on exactly that
 * since the `FW-COST` packet — "prices off its chosen target while cast legality is decided before targets
 * exist" — and both halves of the sentence are now answered:
 *
 * - **CR 601.2 already sequences it correctly.** Targets are chosen at CR 601.2c, the total cost is
 *   determined and locked in at CR 601.2f, and the payment plan is enumerated after both. The pipeline has
 *   followed that order since the `FW-COST` packet moved cost determination above every payment stage, so
 *   nothing about this card moved a stage.
 * - **The castability gate prices the cheapest target choice.** `castIsLegal` runs before any target
 *   exists, so it prices the spell against every target it *could* choose (`cheapestTargetsFor`), which
 *   makes the reduction apply exactly when some legal choice would apply it. Pricing the printed `{4}{W}`
 *   there would hide the two-mana line from a seat holding two mana and facing a tapped creature — ADR-005
 *   in the direction that silently deletes a play, which is the worse one.
 * - **The target request is then narrowed to what the seat can pay for** (`affordableTargetOptions`). The
 *   CR's own answer to "you chose a target you cannot pay for" is the CR 601.2h/CR 728 rewind, which this
 *   engine cannot represent mid-gathering, so the option is not offered — the same gate the kicker
 *   announcement already applies to itself. The narrowing can never empty the list: the option that
 *   achieved the cheapest price is by construction affordable.
 *
 * **Untapping the target in response does not re-price the spell**, and nothing had to be written for that:
 * CR 601.2f locks the total cost in, no player receives priority between the cost being determined and the
 * spell being cast, and the reduction is read once at that point. Untapping it *before* Ride's End is cast
 * is a real play and does make the spell cost five.
 *
 * **The target is re-checked at CR 608.2b like any other**, and the tapped status is *not* part of that
 * check: a target that untaps after the spell is cast is still a legal creature, so a two-mana Ride's End
 * that resolves against an untapped creature is the correct outcome, not a bug. The discount is a cost
 * question, asked once; legality is a targeting question, asked twice.
 */
val ridesEnd: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ride's End",
                manaCost = ManaCost.parse("{4}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_OR_VEHICLE)
        override val costReduction =
            CostReduction.IfTargets(
                amount = RIDES_END_REDUCTION,
                condition = TargetCondition.TAPPED_PERMANENT,
            )
        override val resolution =
            ResolutionEffect { state, context ->
                exilePermanent(state, targetedPermanent(context.targets, "Ride's End"))
            }
    }

/**
 * Torch the Tower — `{R}` Instant. "Bargain. Torch the Tower deals 2 damage to target creature or
 * planeswalker. If this spell was bargained, instead it deals 3 damage to that permanent and you scry 1.
 * If a permanent dealt damage by Torch the Tower would die this turn, exile it instead."
 *
 * A one-mana burn spell whose *third line* is why it is played. Two damage for `{R}` is a commodity; two
 * damage that **exiles** is the gauntlet's cheapest answer to a graveyard deck's recursion, to a Bogles
 * threat that would come back, and to anything worth reanimating. The card is Mono-Red's and Burn's
 * sideboard slot against exactly those decks, and the second line is the upside when a spent artifact,
 * enchantment, or token is lying around anyway.
 *
 * **Bargain is [OptionalAdditionalCost.Bargain]** (CR 702.166a) and needed nothing from this packet — the
 * `FW-BARGAIN` cell shipped with Troublemaker Ouphe. The two cards use it from opposite sides: the Ouphe
 * reads it back as a CR 603.4 [dev.mtgplay.core.definition.InterveningIf] on a trigger, this reads it back
 * *during its own resolution*, off the cast record ([ResolutionContext.optionalCostPaid]), which is where
 * CR 702.166b puts the linked information for a spell that never becomes a permanent.
 *
 * **"Instead it deals 3 damage" is one replacement of the printed effect, not two damage plus one**, and
 * writing it as a branch on the amount is what makes that true. A shield that prevents "the next 2 damage"
 * must see a single 3-damage event from a bargained Torch, and a creature with 2 toughness and a
 * `+0/+1` pump must die to it — both of which come out right only because exactly one [dealDamage] call
 * happens with one amount.
 *
 * **The scry is [ClauseCondition.SpellPaidOptionalAdditionalCost]**, the gate this packet adds. An
 * unbargained Torch the Tower opens **no** library-look pause at all — not a pause with a forced answer —
 * so the enumerated action space is smaller by exactly the arrangements the card does not offer (ADR-005).
 * That is the whole reason the condition is declared rather than folded: a clause is a *pause*, and a
 * pause that should not exist cannot be undone from inside the clause.
 *
 * **The third line is a delayed replacement effect** (CR 614.1a, CR 603.7a), created by this resolution
 * and living in [dev.mtgplay.core.state.GameState.deathReplacements] until the cleanup step. Four things
 * about it are the card rather than the framework:
 *
 * - **It watches the permanent for the whole turn, not just for this damage.** A creature Torched for 2
 *   that survives, blocks later, and takes lethal combat damage is exiled — the rider does not care what
 *   kills it (CR 700.4 "dies" is any battlefield-to-graveyard move), only that it was dealt damage by
 *   this spell. Chump-blocking it away, sacrificing it to Fanatical Offering, or Terminating it in
 *   response all end in exile.
 * - **It ends at CR 514.2 and not before.** A creature that survives the turn goes to the graveyard
 *   normally from the next turn on, which is the line an opponent plays for.
 * - **Prevented damage is not dealt damage** (CR 615.6), so a creature behind Prismatic Strands' red
 *   shield is dealt nothing, is no part of the rider's set, and dies normally later.
 *   [dealDamageThenExileIfItWouldDie] is the primitive that asks, because a card definition cannot.
 * - **A dead target is not damaged either.** The CR 608.2b re-check runs first, so a Torch whose only
 *   target is gone never resolves and creates nothing.
 *
 * **"Target creature or planeswalker" is target creature here**, and it is the same narrowing `W8-C`
 * recorded for Searing Blaze: Pauper is a commons format and CR 306 planeswalkers are not printed at
 * common, so no gauntlet board can hold one. The rider still says *permanent* rather than *creature*,
 * which is not pedantry — the exile catches a creature that stops being one, and the framework is keyed on
 * object ids rather than on a card type for exactly that reason.
 */
val torchTheTower: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Torch the Tower",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)

        // CR 702.166a: "You may sacrifice an artifact, enchantment, or token as you cast this spell."
        override val optionalAdditionalCost = OptionalAdditionalCost.Bargain
        override val resolution =
            ResolutionEffect { state, context ->
                // CR 702.166b: the bargained branch *replaces* the printed damage, so there is exactly one
                // damage event whatever the amount.
                val amount = if (context.optionalCostPaid) TORCH_BARGAINED_DAMAGE else TORCH_DAMAGE
                dealDamageThenExileIfItWouldDie(
                    state = state,
                    source = context.damageSource(),
                    recipient = targetedPermanent(context.targets, "Torch the Tower"),
                    amount = amount,
                )
            }

        // CR 701.17a: "you scry 1" — but only on the bargained branch (CR 702.166b).
        override val libraryLook = LibraryLook(LibraryLookMode.Scry(TORCH_SCRY))
        override val clauseCondition = ClauseCondition.SpellPaidOptionalAdditionalCost
    }

/**
 * Every permanent [targets] names (CR 115.1b), in the order chosen, for a resolution whose spec is a
 * multi-target [TargetSpec.TargetPermanent]. The plural sibling of [targetedPermanent] and deliberately
 * *not* a widening of it: that one asserts a single target and is what every one-target removal spell
 * wants said out loud.
 *
 * A **short** list is a correct input rather than a defect — CR 608.2b lets a spell resolve with some of
 * its targets illegal, doing what it can, so a Dust to Dust whose second artifact has already been
 * sacrificed exiles one. What still fails loudly is a target of the wrong *kind*: the re-check has run, so
 * anything but a [Target.Permanent] here is an engine defect (ADR-005).
 */
private fun targetedPermanents(
    targets: List<Target>,
    cardName: String,
): List<ObjectId> =
    targets.map { target ->
        (target as? Target.Permanent)?.id
            ?: error("CR 115.1b: $cardName targets only battlefield permanents, got $target")
    }

/**
 * Destroys the permanent a fired trigger carried as its [subject] (CR 701.7a) — Cryoshatter's "destroy it"
 * — or does nothing when that permanent is no longer on the battlefield.
 *
 * **The absence check is CR 608.2, not defensiveness.** The subject is the enchanted creature as of the
 * moment the trigger fired (CR 603.10 last-known information), and a triggered ability resolves later: the
 * creature may have died to the very combat damage that fired this (the CR 704.5g state-based action runs
 * before the trigger is put on the stack), or been bounced or exiled in response. "As much as possible" of
 * an instruction that cannot be carried out is nothing at all, so this returns the state unchanged rather
 * than reaching [destroy], which fails loudly off the battlefield by design.
 *
 * A trigger with no subject at all is a different matter and does fail loudly: every condition that fires
 * this ability carries one, so its absence is an engine defect.
 */
private fun destroyTriggerSubject(
    state: GameState,
    subject: ObjectId?,
): GameState {
    val target = subject ?: error("CR 603.10: a 'destroy it' trigger fires with the permanent it acts on")
    return if (state.sharedZones.battlefield.any { it.id == target }) destroy(state, target) else state
}

/**
 * Searing Blaze — `{R}{R}` Instant.
 * "Searing Blaze deals 1 damage to target player or planeswalker and 1 damage to target creature that
 * player or that planeswalker's controller controls.
 * Landfall — If you had a land enter the battlefield under your control this turn, Searing Blaze deals 3
 * damage to that player or planeswalker and 3 damage to that creature instead."
 *
 * **Two instances of the word "target", and the second depends on the first.** That is the whole reason
 * the card was blocked twice over, and both halves are now framework rather than card:
 * [additionalTargetSpecs] gives the spell a second targeting line, and
 * [PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER] reads the first line's answer out of the
 * [dev.mtgplay.core.definition.TargetContext] the engine threads into the enumeration. The engine asks for
 * the player first and then offers only that player's creatures — which is CR 601.2c's printed order and
 * is not a choice this definition makes.
 *
 * **"Target player or planeswalker" is encoded as target player, and that is exact rather than a
 * narrowing.** Pauper's card pool contains no planeswalker (CR 306 — planeswalkers are rare or mythic and
 * never common), and none is in the gauntlet, so the disjunction has exactly one live arm. Saying so here
 * rather than silently dropping the word is the point: if a planeswalker ever entered the pool this line
 * would need a [TargetSpec] admitting both, and the second line's "that player **or that planeswalker's
 * controller**" would need to resolve a controller rather than read a player id.
 *
 * **It cannot be cast without a creature to point at.** CR 601.2c requires every instance of the word
 * "target" to have a legal choice, so a Searing Blaze with an empty battlefield is simply not an option in
 * the priority window — and the gate is a *search*, not two independent tests: the card is castable
 * exactly when some player has a creature its caster may target, which is why a hexproof-only board makes
 * it uncastable even though both a player and a creature exist.
 *
 * **Landfall is checked on resolution, not on cast** (CR 608.2). Playing a land after casting Searing
 * Blaze but before it resolves is not possible in a priority window, but *another* land entering — a
 * search, a return — during the response is, so the read has to be live. It reads
 * [dev.mtgplay.core.state.PlayerState.landsEnteredThisTurn], which counts **entries** rather than land
 * drops: a land put onto the battlefield without being played still turns landfall on, and encoding it
 * against the land-drop counter would be a wrong card in a gauntlet holding fetch effects.
 *
 * **The damage is one amount applied twice, not two decisions.** Both halves scale together — 1 and 1, or
 * 3 and 3 — so landfall is read once and the same number is dealt to each target, in printed order
 * (player first). Dealing them in that order matters: damage to a player can end the game, and the CR's
 * own order is the printed one.
 *
 * **A partly-illegal Searing Blaze still resolves** (CR 608.2b): if the creature has died in response but
 * the player is still there, the spell resolves and deals its damage to the player alone. Only when
 * *every* target is illegal does it fail to resolve — which for this card means the creature is gone and
 * the player has left the game, i.e. the game is over (CR 104.2a). The effect therefore looks each target
 * up rather than assuming both are present.
 */
val searingBlaze: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Searing Blaze",
                manaCost = ManaCost.parse("{R}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED

        // CR 115.1a: the first printed instance of the word "target". "Or planeswalker" has no live arm
        // in Pauper (CR 306) — see the KDoc, which says so rather than letting the omission pass silently.
        override val targetSpec = TargetSpec.TargetPlayer(TargetCount.ONE)

        // CR 115.1b: the second instance, dependent on the first (`W9-C`).
        override val additionalTargetSpecs =
            persistentListOf<TargetSpec>(
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_CONTROLLED_BY_TARGETED_PLAYER),
            )
        override val resolution =
            ResolutionEffect { state, context ->
                // CR 702.135a / CR 608.2: landfall is a fact about *this turn*, read as the spell
                // resolves rather than as it was cast.
                val landfall = hadLandEnterThisTurn(state, context.controller)
                val damage = if (landfall) SEARING_BLAZE_LANDFALL_DAMAGE else SEARING_BLAZE_DAMAGE
                // CR 608.2b: a target that has become illegal is skipped and the rest of the spell still
                // happens, so each is dealt with independently and in printed order.
                context.targets.fold(state) { current, target ->
                    dealDamage(current, context.damageSource(), target, damage)
                }
            }
    }
