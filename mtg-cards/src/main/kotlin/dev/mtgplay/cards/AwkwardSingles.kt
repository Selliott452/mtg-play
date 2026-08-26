package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.OptionalTapOrUntap
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TargetingRequirement
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyIndefinitely
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.putCounters
import dev.mtgplay.rules.effect.skipNextCombatPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * `W8-G`: the gauntlet cards that belong to no family — each one awkward in its own way, and each one
 * the card a themed packet skipped. Four of them are here; what they have in common is only that every
 * one of them had been picked up and put down at least once before.
 *
 * - **Stonehorn Dignitary** (UWX Familiar) — "target opponent skips their next combat phase". Dropped by
 *   `FW-TAPUNTAP`, whose diagnosis was exact: `positionAfter` decided skipping from the [Turn] alone and
 *   had no way to see a fact about a *player*. That is now the whole of the fix — the phase-skip framework
 *   is one field on `PlayerState`, one clause in `isSkipped`, and one decrement.
 * - **Standard Bearer** (GW Bogles' sideboard) — a CR 601.2c targeting **requirement**, and the first
 *   thing in the pool that changes what an *opponent* is allowed to point at.
 * - **Sewer-veillance Cam** (Grixis Affinity, Mono Blue Faeries) — dropped by `FW-TAPUNTAP` as needing
 *   modal resolution on a triggered ability. **That diagnosis was wrong**, and its own KDoc says why.
 * - **Kenku Artificer** (Grixis Affinity) — added by `FW-TYPECHANGE`, which is the framework `W8-G`
 *   diagnosed it as needing and then declined to build. Its diagnosis was exact and is worth keeping in
 *   view: CR 613 layer 4 was a *declared but unpopulated* member of the layer walk, and the thing that
 *   made populating it real work rather than a one-line addition was that `LayeredCharacteristics`, the
 *   value the walk threads, carried no card types or subtypes at all. Filling it took two new fields on
 *   that type, four on `ActiveEffect`, sublayer 7b alongside it (the 0/0 is a CR 613.4b set-P/T), an
 *   **indefinite** [dev.mtgplay.core.state.EffectDuration] — CR 611.2b's "no duration" default, which the
 *   engine could not represent — and the rerouting of six battlefield card-type reads onto the layer
 *   engine.
 *
 * Bonder's Ornament, `W8-G`'s fourth encoded card, landed in ColorlessArtifacts.kt beside the header
 * paragraph that had been recording it absent for a reason that stopped being true two packets ago.
 * **Sacred Cat**, the second card `W8-G` dropped, is now in Embalm.kt: its diagnosis was exact too, and
 * the framework it named — token identity keyed on a name a copy token shares with its card — is
 * `FW-COPYTOKEN`. That file's header carries the argument, including the *fourth* blocker `W8-G` did not
 * find: the token is "a white Zombie Cat with **no mana cost**", and colour was derived from the mana
 * cost with a single CDA exception, so white-and-costless had no representation at all.
 *
 * **The one still dropped, and exactly what it would need.** It was written far enough to know the
 * answer, and it is a framework this packet does not own; an approximation of it would be a
 * plausible-looking wrong card (PLAN.md §7).
 *
 * - **Inventor's Axe** `{R}` — "Flash. When this Equipment enters, you get `{E}{E}`. When this Equipment
 *   enters, attach it to target creature you control. Equipped creature gets +2/+0. Equip—Pay `{E}{E}`."
 *   Two frameworks, and the packet that owns the layer system can now say precisely which half is which.
 *
 *   **The buff is not the problem and never was.** "Equipped creature gets +2/+0" is an ordinary
 *   CR 613 sublayer-7c static over [dev.mtgplay.core.definition.AffectedSet.Enchanted], which is what
 *   `attachedTo` already means; a hypothetical Equipment that entered attached and never moved would
 *   need nothing this engine lacks.
 *
 *   **`FW-EQUIP` is the blocker**, and it is about *attachment as a verb* rather than as a field. Every
 *   path that writes `attachedTo` is Aura-shaped: attachment happens as an Aura **spell resolves**
 *   against a `TargetSpec.Enchantable`, once, at the moment the permanent enters. Equip is an activated
 *   ability with sorcery timing that **moves** an attachment on an already-resolved permanent, and there
 *   is no primitive that attaches or unattaches a permanent already on the battlefield — not even for the
 *   card's own "When this Equipment enters, attach it to target creature you control", which is a
 *   *trigger* doing what only a resolving Aura spell can do today. CR 704.5n is then a second, opposite
 *   state-based action to the Aura's CR 704.5m: an Equipment attached to an illegal permanent becomes
 *   **unattached** and stays on the battlefield, where an Aura is put into its owner's graveyard. The two
 *   SBAs cannot share an implementation, and `AuraFallOff.kt` currently is the implementation.
 *
 *   **Energy** (CR 122.1) is the smaller half and is deliberately named second: it is a counter on the
 *   *player*, and [dev.mtgplay.core.state.PlayerState] has no counter storage of any kind, so it needs a
 *   store, an `AbilityCost.Energy`, and that cost's payability and payment in the activation pipeline.
 *   That is a contained addition. **The card is dropped for equip, not for energy** — building energy
 *   alone would leave a permanent that can never attach to anything.
 */

/**
 * Stonehorn Dignitary — `{3}{W}` Creature — Rhino Soldier, a 1/4 whose enters-the-battlefield trigger
 * (CR 603.6a) makes **target opponent** skip their next combat phase (CR 500.10).
 *
 * UWX Familiar's lock piece, and the reason the phase-skip framework was worth building rather than
 * routing around: the deck does not play a 1/4 for four because it wants a 1/4. It plays it because
 * Ephemerate and Ghostly Flicker re-trigger the enters ability, and a scheduled skip **stacks** — two
 * blinks are two combat phases the opponent does not get. Encoding the skip as a boolean marker (the
 * shape [dev.mtgplay.rules.effect.skipNextUntapStep] uses, and the obvious thing to copy) would have
 * absorbed the second one silently and deleted the deck's entire plan while looking correct.
 *
 * **The skip is a modification of the turn structure, not a continuous effect**, which is why nothing
 * here goes near the layer system: docs/design/duration.md §12 lists this exact card among the layer
 * framework's explicit non-goals. Two consequences an agent can observe:
 * - **Killing the Dignitary does not give the phase back.** The trigger has resolved and the obligation
 *   sits on the player; the source is irrelevant from that moment on.
 * - **"Their next" means the affected player's own next turn**, because a combat phase belongs to
 *   whoever is active (CR 506.1). Resolving this on your own turn costs the opponent *their* next combat,
 *   not the one currently in progress.
 *
 * A 1/4 body is deliberately defensive: the card is bought for the trigger, and the toughness is what
 * lets it survive to be blinked. It targets an opponent rather than a player, so in a two-player game the
 * choice is forced and the engine offers exactly one option (CR 115.1a) — the trigger still goes on the
 * stack and is still respondable, which is the window an opponent has to kill the Dignitary and change
 * nothing at all.
 */
val stonehornDignitary: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Stonehorn Dignitary",
                manaCost = ManaCost.parse("{3}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Rhino"), Subtype("Soldier")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 4),
            )

        // CR 302.1: the creature spell is untargeted and sorcery-speed; the *ability* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.TargetOpponent,
                    effect =
                        ResolutionEffect { state, context ->
                            val target = context.targets.single()
                            check(target is Target.Player) {
                                "CR 115.1a: Stonehorn Dignitary's trigger targets an opponent, got $target"
                            }
                            skipNextCombatPhase(state, target.id)
                        },
                ),
            )
    }

/** The creature type Standard Bearer's requirement names (CR 205.3m). */
private val FLAGBEARER: Subtype = Subtype("Flagbearer")

/**
 * Standard Bearer — `{1}{W}` Creature — Human Flagbearer, a 1/1 whose static ability says: "While an
 * opponent is choosing targets as part of casting a spell they control or activating an ability they
 * control, that player must choose at least one Flagbearer on the battlefield if able."
 *
 * GW Bogles' sideboard card against removal, and the pool's first **targeting requirement** (CR 601.2c).
 * Everything else in the engine that touches an opponent's target choice *removes* options — hexproof,
 * protection, a spec's own restriction. This adds none and removes none: it narrows the choice to a set
 * the opponent would rather not choose from, which is why docs/design/protection.md §8 argued it out of
 * the protection framework and into its own.
 *
 * **The printed scope is narrower than it reads, and the narrowness is the card.** It binds *casting a
 * spell* and *activating an ability*, and it does not mention triggered abilities — so a Harrier Strix's
 * enters-the-battlefield trigger may still tap whatever it likes, and a Lotleth Giant's may still point
 * where it likes. The engine gets that from where [TargetingRequirement] is consulted rather than from a
 * flag: `announceableTargets` binds the four cast/activate announcement sites and `TriggerTargeting.kt`
 * does not call it.
 *
 * **"If able" is CR 601.2c's restrictions-beat-requirements clause**, and against Bogles it is the whole
 * interaction: every creature that deck wants to protect has hexproof, so a Standard Bearer on *their*
 * side of the table is the only legal creature target its opponent has — a Terminate must kill the 1/1
 * rather than the Slippery Bogle wearing four Auras. Turn it around and a Standard Bearer facing a
 * hexproof board is a requirement nobody can obey and it does nothing at all.
 *
 * **It requires a *Flagbearer*, not itself**, which is why the declaration carries a [Subtype]. Two
 * Standard Bearers do not double the constraint (obeying one obeys both), and a changeling — Rooftop
 * Percher is in the pool — counts as a Flagbearer and is offered as one (CR 702.73a).
 *
 * The 1/1 body has no abilities of its own. Nothing about the requirement is a continuous effect on any
 * object, so this card declares no [dev.mtgplay.core.definition.StaticContinuousEffect] and never reaches
 * the CR 613 layer engine.
 */
val standardBearer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Standard Bearer",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), FLAGBEARER),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 604.3: a static ability functioning from the battlefield, constraining opponents only.
        override val targetingRequirements = persistentListOf(TargetingRequirement(FLAGBEARER))
    }

/** How many cards Sewer-veillance Cam's sacrifice ability draws (CR 120.1). */
const val SEWER_VEILLANCE_CAM_DRAW: Int = 2

/** The creature Sewer-veillance Cam's trigger may tap or untap (CR 115.1b). */
private val TARGET_CREATURE: TargetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)

/**
 * Sewer-veillance Cam — `{U}` Artifact. "Flash. When this artifact enters **or leaves** the battlefield,
 * you may tap or untap target creature. `{3}{U}`, Sacrifice this artifact: Draw two cards."
 *
 * Grixis Affinity's and Mono Blue Faeries' one-mana tempo artifact, dropped once by `FW-TAPUNTAP` on a
 * diagnosis that was **wrong** — worth recording, because the correction is a rules reading and not an
 * engine change. That packet filed the resolution as "a *mode* choice on a triggered ability" needing
 * `FW-MODAL`, which handles modes on spells only. CR 700.2 makes an object modal only when it prints two
 * or more options in a bulleted list preceded by an instruction to choose among them; this card prints
 * one sentence with a conjunction, so it is not modal, no mode is announced at CR 601.2b, and the choice
 * belongs where the card puts it — in the resolution (CR 608.2c). What it needed was a mid-resolution
 * decision, which is [dev.mtgplay.core.definition.ResolutionClauses]' whole subject; the clause is
 * [OptionalTapOrUntap], whose KDoc carries the argument in full.
 *
 * **Three printed things, and the middle one is the card.**
 * - **Flash** is [TimingClass.INSTANT_SPEED] on a permanent spell (CR 702.8a), the pool's established
 *   spelling — there is no `Keyword.FLASH`, by design, because flash *is* a timing permission.
 * - **"Enters or leaves"** is one printed ability with two conditions, encoded as two [TriggeredAbility]
 *   entries for [ichorWellspring]'s reason and with [ichorWellspring]'s argument: the events are
 *   disjoint, so exactly one of the pair can match any given event and the observable behaviour is
 *   identical to the single two-condition ability. The second half is
 *   [TriggerCondition.LeftBattlefieldSelf] rather than the narrower put-into-a-graveyard condition, and
 *   that matters more here than it does for the Wellspring: this artifact's *own* third ability
 *   sacrifices it, and an affinity board also loses artifacts to exile and to bounce. Per CR 603.6c/d
 *   the leaves trigger is checked against the state just before it left and carries the departed
 *   artifact's last known information.
 * - **The sacrifice draw** is an ordinary CR 602 activated ability costing `{3}{U}` plus
 *   [AbilityCost.SacrificeSelf] — and paying it *fires the second trigger*, which is the card's actual
 *   line: two cards and a tap, for four mana, at instant speed.
 *
 * **Both halves target, and a Cam entering an empty board still resolves.** The target is chosen as each
 * trigger goes on the stack (CR 603.3d); with no creature anywhere the trigger is placed with no target
 * and the clause does nothing (CR 608.2c), which is why nothing here is conditional on the board.
 *
 * **The tap half is a combat trick and the untap half is a mana trick**, and an engine offering only one
 * of them would be a different card in both decks: Faeries flashes it in to tap a blocker, Affinity
 * untaps a land or a Myr Enforcer that has already attacked. Declining is the third real answer, most
 * obviously when the only creature on the board is one its controller would rather leave untapped.
 */
val sewerVeillanceCam: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sewer-veillance Cam",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        // CR 702.8a: flash is the permission to cast at instant speed, not a keyword on the card.
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TARGET_CREATURE,
                    effect = ResolutionEffect { state, _ -> state },
                    optionalTapOrUntap = OptionalTapOrUntap,
                ),
                TriggeredAbility(
                    // CR 603.6c: every departure, not only the one that ends in a graveyard.
                    condition = TriggerCondition.LeftBattlefieldSelf,
                    targetSpec = TARGET_CREATURE,
                    effect = ResolutionEffect { state, _ -> state },
                    optionalTapOrUntap = OptionalTapOrUntap,
                ),
            )

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{3}{U}")),
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, SEWER_VEILLANCE_CAM_DRAW)
                        },
                ),
            )
    }

/** The subtype Kenku Artificer's layer-4 change grants (CR 205.3m). */
private val HOMUNCULUS: Subtype = Subtype("Homunculus")

/** How many `+1/+1` counters Kenku Artificer's trigger places (CR 122.1a). */
private const val KENKU_ARTIFICER_COUNTERS: Int = 3

/**
 * Kenku Artificer — `{2}{U}` Creature — Bird Artificer, a 1/1 whose enters-the-battlefield trigger
 * (CR 603.6a) reads: *"Homunculus Servant — When this creature enters, put three `+1/+1` counters on
 * up to one target noncreature artifact. That artifact becomes a 0/0 Homunculus artifact creature with
 * flying."*
 *
 * Grixis Affinity's payoff, and the card that finally populated CR 613 **layer 4**. It had been picked
 * up and put down three times, and the packet that dropped it last was right about every blocker and
 * right that the slot's emptiness was the problem: `Layer.TYPE` was a declared member of the layer walk,
 * but `LayeredCharacteristics`, the value the walk threads, carried power, toughness, keywords, mana
 * abilities, protections and evasions and **no card types or subtypes at all**, so there was nothing for
 * a layer-4 effect to write to.
 *
 * **The card is four separate continuous-effect contributions in one sentence**, and putting each in the
 * layer that owns it is the whole of encoding it:
 * - "becomes a … artifact creature" and "Homunculus" are CR 613.1d **layer 4**, and they *add* to the
 *   type line rather than replacing it (CR 205.1b) — the target stays an artifact, and an artifact
 *   *land* stays a land;
 * - "0/0" is a CR 613.4b **sublayer 7b** set;
 * - "with flying" is a CR 613.1f **layer 6** grant;
 * - the three `+1/+1` counters are CR 122.1a and land in **sublayer 7c**, which CR 613.4c names for
 *   "effects *and counters* that modify power and/or toughness".
 *
 * **The artifact ends up a 3/3 rather than a corpse, and the reason is sublayer order, not timing.** A
 * 0/0 creature is destroyed by the CR 704.5f state-based action, so an engine that applied the counters
 * before the set — or folded the set into 7c, where the two contributions would commute — would kill the
 * artifact the instant the trigger resolved and the card would do nothing at all. Sublayer 7b runs
 * strictly before 7c, so the walk sets 0/0 and *then* adds `+3/+3`. State-based actions are not checked
 * during a resolution (CR 704.3), so the intermediate 0/0 is never observed even in principle.
 *
 * **The type change has no duration, which is a distinct fact from lasting a long time** (CR 611.2b).
 * The printed text says "becomes", not "becomes … until end of turn", so the effect lasts as long as the
 * game does — [dev.mtgplay.rules.effect.applyIndefinitely], the primitive this card needed because the
 * engine could previously represent only "until end of turn", and encoding this as a turn-long change
 * would have produced a card that quietly un-does itself in the cleanup step with nothing in any log to
 * say so.
 *
 * **"Up to one target" is a real choice with three answers, and the third one matters.** Choosing zero
 * targets is legal ([dev.mtgplay.core.definition.TargetCount.UpTo]) — the Artificer still enters as a
 * 1/1 — and it is the right answer when every artifact you control is one you would rather leave alone:
 * an animated artifact starts dying to creature removal that could not touch it before, and it stops
 * being a legal target for the next Kenku Artificer. The counters and the change are one instruction
 * about one artifact, so "counters here, animation there" is not among the answers.
 *
 * **Its target must be a *noncreature* artifact, read in-game.**
 * [PermanentRestriction.NONCREATURE_ARTIFACT] consults the layered type line, so an artifact a previous
 * Kenku Artificer already animated is not offered — and animating one in response makes an
 * already-announced trigger fizzle at the CR 608.2b re-check.
 *
 * **What the target loses is worth as much as what it gains.** An animated artifact can attack the turn
 * it is animated if it has been under its controller's continuous control since their turn began
 * (CR 302.6) — that is the Affinity line, a Myr Enforcer's worth of stats appearing on a Bauble — but it
 * also becomes vulnerable to every creature-shaped answer in the opponent's deck.
 */
val kenkuArtificer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Kenku Artificer",
                manaCost = ManaCost.parse("{2}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Bird"), Subtype("Artificer")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        // CR 302.1: the creature spell itself is untargeted and sorcery-speed; the *ability* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec =
                        TargetSpec.TargetPermanent(
                            restriction = PermanentRestriction.NONCREATURE_ARTIFACT,
                            count = TargetCount.UpTo(1),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 115.1: "up to one" may be zero, and a trigger that chose no target
                            // still resolves and simply does nothing (CR 608.2c).
                            val target = context.targets.singleOrNull() ?: return@ResolutionEffect state
                            check(target is Target.Permanent) {
                                "CR 115.1b: Kenku Artificer's trigger targets a noncreature artifact, got $target"
                            }
                            animateArtifact(state, target.id, context.sourceCard)
                        },
                ),
            )
    }

/**
 * Kenku Artificer's whole resolution on the chosen [artifact]: three `+1/+1` counters (CR 122.1a), then
 * the durationless CR 613 layer-4/7b/6 change that makes it a 0/0 Homunculus artifact creature with
 * flying (CR 611.2b).
 *
 * **The order of the two statements is unobservable; that both happen is not.** No player receives
 * priority and no state-based action is checked inside a resolution (CR 704.3), so the artifact is never
 * seen as a 0/0. What is load-bearing is the pairing: counters without the type change would be inert
 * (CR 122.1a has nothing to modify on a noncreature), and the layer walk fails loudly on that rather
 * than ignoring them — which is the guard that stops a half-applied resolution from looking like a
 * merely smaller creature.
 *
 * @param sourceCard the trigger's own printed identity (CR 113.7c); the fallback names the card
 *   directly, because a continuous effect must be attributable in the log even when the source object
 *   is long gone.
 */
private fun animateArtifact(
    state: GameState,
    artifact: ObjectId,
    sourceCard: CardRef?,
): GameState {
    val withCounters = putCounters(state, artifact, Counter.PLUS_ONE_PLUS_ONE, KENKU_ARTIFICER_COUNTERS)
    return applyIndefinitely(
        withCounters,
        affected = artifact,
        modification =
            ContinuousModification(
                // CR 613.1f: "with flying".
                grantedKeywords = persistentSetOf(Keyword.FLYING),
                // CR 613.1d: gained *in addition to* the artifact type the permanent already has.
                addedCardTypes = persistentSetOf(CardType.CREATURE),
                addedSubtypes = persistentSetOf(HOMUNCULUS),
                // CR 613.4b: "a 0/0", set in sublayer 7b so the 7c counters land on top of it.
                setPower = 0,
                setToughness = 0,
            ),
        sourceCard = sourceCard ?: KENKU_ARTIFICER_REF,
    )
}

/** Kenku Artificer's printed identity, for the CR 113.7c fallback in [animateArtifact]. */
private val KENKU_ARTIFICER_REF: CardRef = CardRef("Kenku Artificer")
