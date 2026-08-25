package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
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

/** The artifacts Dust to Dust exiles (CR 115.1). */
private const val DUST_TO_DUST_TARGETS: Int = 2

/** Cryoshatter's layer-7c power modifier (CR 613.3, sublayer 7c). */
private const val CRYOSHATTER_POWER_MOD: Int = -5

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
