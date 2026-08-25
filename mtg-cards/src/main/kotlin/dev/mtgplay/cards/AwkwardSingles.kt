package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.OptionalTapOrUntap
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TargetingRequirement
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.skipNextCombatPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * `W8-G`: the gauntlet cards that belong to no family — each one awkward in its own way, and each one
 * the card a themed packet skipped. Three of them are here; what they have in common is only that every
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
 *
 * Bonder's Ornament, the packet's fourth encoded card, landed in ColorlessArtifacts.kt beside the header
 * paragraph that had been recording it absent for a reason that stopped being true two packets ago.
 *
 * **The three the packet dropped, and exactly what each would need.** Each was written far enough to know
 * the answer, and each is a framework this packet does not own; an approximation of any of them would be
 * a plausible-looking wrong card (PLAN.md §7).
 *
 * - **Kenku Artificer** `{2}{U}` — "put three `+1/+1` counters on up to one target noncreature artifact.
 *   That artifact becomes a 0/0 Homunculus artifact creature with flying." The counters and the targeting
 *   are both ready; the type change is not. `Layer.TYPE` exists as a declared member of the layer walk,
 *   which makes this look like filling an empty slot — it is not, because
 *   `LayeredCharacteristics`, the value the walk threads, has fields for power, toughness, keywords, mana
 *   abilities, protections and evasions and **no card types or subtypes at all**. Populating CR 613
 *   layer 4 means widening that type and `ActiveEffect`, populating sublayer 7b as well (the 0/0 is a
 *   CR 613.4b set-P/T, also unpopulated), inventing an **indefinite** [dev.mtgplay.core.state.EffectDuration]
 *   (it is sealed with `UntilEndOfTurn` alone, and this change never ends), and rerouting the
 *   battlefield-object card-type reads in `PermanentRestrictions.kt`, `EnchantRestrictions.kt`,
 *   `EffectiveCharacteristics.kt`, `ObjectCount.kt`, `PermanentCount.kt`, `SacrificeCosts.kt` and
 *   `ActionEnumeration.kt`, every one of which currently reads printed *and says in its own KDoc that it
 *   does so because no type-changing effect exists*. The SBA half is already honest: `Layers.kt`'s
 *   `modifiedByCounters` names this card and fails loudly on P/T counters with no P/T box rather than
 *   dropping them, so the CR 704.5f ordering this card turns on cannot be got wrong by accident.
 * - **Sacred Cat** `{W}` — "Lifelink. Embalm `{W}`." The body is printed vocabulary. Embalm (CR 702.90a)
 *   needs four absent things, and the fourth is the interesting one: an `AbilityZoneScope.Graveyard` (the
 *   enum has `Battlefield` and `Hand` only), exiling the card itself from the graveyard as a cost,
 *   sorcery-timing on an activated ability, and a **token that copies a card** with altered
 *   characteristics. [dev.mtgplay.core.definition.TokenDefinition] is a hand-authored characteristics blob
 *   registered under its own name, and "this object is a token" is `definitions[card] is TokenDefinition`
 *   — so an embalm token named "Sacred Cat" would land on the registry entry the *real card* already
 *   occupies, and the create-token primitive's register-if-absent would silently give the token the card's
 *   definition: castable, embalmable again, and invisible to the CR 704.5d token-ceases state-based
 *   action. The blocker is not "tokens do not exist"; it is that this engine keys token identity on the
 *   name a copy token shares with its card.
 * - **Inventor's Axe** `{R}` — "Flash. …you get `{E}{E}`… attach it to target creature you control.
 *   Equipped creature gets +2/+0. Equip—Pay `{E}{E}`." Four absent concepts on one uncommon, in two
 *   frameworks. **Equipment** (CR 301.5, CR 702.6): the attachment *field* exists
 *   (`GameObject.attachedTo`, and the Aura buff is an ordinary layer-6/7c static), but every path that
 *   writes it is Aura-shaped — attachment happens as an Aura *spell resolves* against a
 *   `TargetSpec.Enchantable`, and equip is an activated ability with sorcery timing that **moves** an
 *   attachment between creatures on an already-resolved permanent, which nothing can express; CR 704.5n
 *   (an Equipment attached to an illegal permanent becomes unattached, rather than the Aura's CR 704.5m
 *   graveyard trip) is a second, opposite SBA. **Energy** (CR 122.1) is a counter on the *player*, and
 *   `PlayerState` has no counter storage of any kind, plus an `AbilityCost.Energy` and its payment
 *   enumeration. Of the two, Equipment is the one worth building; energy unlocks this card alone.
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
