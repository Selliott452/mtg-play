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
import dev.mtgplay.rules.effect.dealDamageThenExileIfItWouldDie
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exilePermanent
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
 * - **Searing Blaze** `{R}{R}` — "deals 1 damage to target player or planeswalker **and** 1 damage to
 *   target creature that player or that planeswalker's controller controls." Two things, either alone
 *   sufficient to keep it out. (1) It prints **two separate instances of the word "target"**, and
 *   [TargetCount]'s own KDoc records that as deliberately unmodelled: a spec is one noun with one
 *   cardinality, and a card with two targeting lines needs a *list* of them
 *   (docs/design/multi-target.md §7). (2) The second instance is **dependent** on the first — its legal
 *   set is "creatures that specific player controls" — so even a list of lines would not do: enumerating
 *   line two requires line one's answer, which is a CR 601.2c ordering the gathering does not have. Its
 *   landfall clause is a third, smaller gap ("a land entered the battlefield under your control this
 *   turn" is a per-turn fact [dev.mtgplay.core.state.Turn] does not keep, checked on *resolution* per
 *   CR 608.2), and it is not worth adding for a card blocked twice over.
 *
 *   One correction to the packet brief: **"target player or planeswalker" is target player here**, since
 *   Pauper prints no planeswalker (CR 306) and none is in the gauntlet — but that narrowing is not what
 *   keeps the card out and does not shrink the gap.
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
