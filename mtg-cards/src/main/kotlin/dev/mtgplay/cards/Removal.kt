package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's targeted removal (docs/decklists.md): the spells that answer a permanent by
 * destroying it (CR 701.7) or exiling it (CR 701.3). Cast Down and Terminate for Jund Wildfire and
 * Grixis, the two artifact answers Smash to Smithereens and Ancient Grudge, Monster Tron's
 * catch-all Scour from Existence, and UWX Familiar's Last Breath.
 *
 * This packet added the primitives that make the family expressible, all in `mtg-rules`
 * (ADR-003): the CR 701.7a [destroy] effect — the engine's first destruction that is not the
 * CR 704.5g state-based action, and which honours indestructible (CR 702.12b) through the one
 * effective-keyword seam — the CR 701.3a [exilePermanent] effect, and
 * [TargetSpec.TargetPermanent], the "target <permanent>" spec whose
 * [PermanentRestriction] carries the noun each card prints.
 *
 * Two cards of the same family are deliberately **not** here, because each needs a framework rather
 * than a primitive, and an approximation of either would be a plausible-looking wrong card
 * (PLAN.md §7):
 * - **Raze** ("As an additional cost to cast this spell, sacrifice a land. Destroy target land.")
 *   needs an [dev.mtgplay.core.definition.AdditionalCost] member for a sacrifice, which is a new
 *   enumerated choose-a-permanent decision in the casting pipeline, not a card-side composition.
 * - **Cryoshatter** ("Enchant creature. Enchanted creature gets -5/-0. When enchanted creature
 *   becomes tapped or is dealt damage, destroy it.") needs two new
 *   [dev.mtgplay.core.definition.TriggerCondition] members and the detection sites to match them:
 *   nothing in the engine watches a permanent *becoming tapped* or *being dealt* damage.
 *
 * See the packet report for what each needs in full.
 */

/** The damage Smash to Smithereens deals to the destroyed artifact's controller (CR 120.3a). */
const val SMASH_TO_SMITHEREENS_DAMAGE: Int = 3

/** The life Last Breath's exiled creature's controller gains (CR 119.3). */
const val LAST_BREATH_LIFEGAIN: Int = 4

/**
 * Cast Down — `{1}{B}` Instant. "Destroy target nonlegendary creature." The narrowest destroy in the
 * pool: [PermanentRestriction.NONLEGENDARY_CREATURE] excludes a creature whose printed supertypes
 * include legendary (CR 205.4), which no card in the gauntlet does — so the exclusion is currently
 * vacuous in play, and is modelled anyway rather than encoding the card as plain "target creature"
 * and quietly printing a line it does not have.
 */
val castDown: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Cast Down",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.NONLEGENDARY_CREATURE)
        override val resolution =
            ResolutionEffect { state, context -> destroy(state, targetedPermanent(context.targets, "Cast Down")) }
    }

/**
 * Terminate — `{B}{R}` Instant. "Destroy target creature. It can't be regenerated." Unconditional
 * removal for two mana, and the Grixis/Jund staple.
 *
 * **The regeneration clause is a genuine no-op, not an omission.** The engine models no CR 701.15
 * regeneration shield — no card in the pool creates one — so [destroy] is regeneration-free by
 * construction and Terminate's second sentence subtracts nothing from it (docs/gauntlet-card-triage.md
 * trap T8). Encoding it as a flag today would be a flag no code could read. When regeneration lands
 * it lands as a replacement effect consulted inside [destroy], and Terminate is the card that opts
 * out of it; until then this definition is exactly the printed card's behaviour.
 */
val terminate: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Terminate",
                manaCost = ManaCost.parse("{B}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context -> destroy(state, targetedPermanent(context.targets, "Terminate")) }
    }

/**
 * Smash to Smithereens — `{1}{R}` Instant. "Destroy target artifact. Smash to Smithereens deals 3
 * damage to that artifact's controller." Two clauses over one target, and the reason it is a Mono-Red
 * sideboard card against affinity rather than plain artifact removal.
 *
 * **"That artifact's controller" is last-known information (CR 608.2h).** The controller is read from
 * the battlefield *before* [destroy] moves the artifact; reading it afterwards would find nothing.
 * The damage is dealt whether or not the destruction succeeded — an indestructible artifact
 * (CR 702.12b) survives and its controller still takes three, which is the printed card and is the
 * whole reason the two clauses are ordered. Control is ownership in the MVP pool
 * (docs/design/layer-system.md §4).
 */
val smashToSmithereens: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Smash to Smithereens",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
        override val resolution =
            ResolutionEffect { state, context ->
                val target = targetedPermanent(context.targets, "Smash to Smithereens")
                // CR 608.2h: capture the controller while the artifact is still on the battlefield.
                val controller = controllerOfTargetedPermanent(state, target, "Smash to Smithereens")
                val destroyed = destroy(state, target)
                dealDamage(destroyed, Target.Player(controller), SMASH_TO_SMITHEREENS_DAMAGE)
            }
    }

/**
 * Ancient Grudge — `{1}{R}` Instant. "Destroy target artifact. Flashback `{G}`." One artifact answer
 * printed twice: the flashback half ([CastingPermission.Flashback], CR 702.34) casts it from the
 * graveyard for a single green mana and exiles it as it leaves the stack (CR 702.34e), which is why
 * a Jund sideboard runs one copy rather than two.
 *
 * Its colour identity is red *and* green (CR 903.4) even though its face is mono-red, because the
 * flashback cost is green — a distinction nothing in the engine reads yet, but a real deck-legality
 * fact for a Commander-style format and the reason the card sits in a Jund list.
 */
val ancientGrudge: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ancient Grudge",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
        override val resolution =
            ResolutionEffect { state, context -> destroy(state, targetedPermanent(context.targets, "Ancient Grudge")) }
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{G}")))
    }

/**
 * Scour from Existence — `{7}` Instant. "Exile target permanent." Monster Tron's colourless
 * catch-all: the cleanest exercise of both new primitives at once, and the only answer in the pool
 * that beats indestructible — exiling is not destroying, so CR 702.12b never applies (CR 701.3a).
 *
 * [PermanentRestriction.ANY_PERMANENT] is the widest restriction there is: every permanent on the
 * battlefield is a legal choice, land and Aura included, subject only to hexproof (CR 702.11).
 */
val scourFromExistence: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Scour from Existence",
                manaCost = ManaCost.parse("{7}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
        override val resolution =
            ResolutionEffect { state, context ->
                exilePermanent(state, targetedPermanent(context.targets, "Scour from Existence"))
            }
    }

/**
 * Last Breath — `{1}{W}` Instant. "Exile target creature with power 2 or less. Its controller gains
 * 4 life." Exile rather than destroy, and lifegain for the *opponent* — the drawback that keeps a
 * one-mana-plus-white answer honest.
 *
 * **"Power 2 or less" reads in-game power (CR 613 sublayer 7c), not printed power.** The restriction
 * lives in [PermanentRestriction.CREATURE_POWER_2_OR_LESS] and is therefore re-checked at CR 608.2b
 * like every other target: a creature pumped in response — by an Aura, in this pool — stops being a
 * legal target and Last Breath does not resolve at all, so nobody gains the 4 life
 * (docs/gauntlet-card-triage.md trap T14).
 *
 * "Its controller" is last-known information (CR 608.2h), captured before the exile, and is
 * ownership in the MVP pool (docs/design/layer-system.md §4).
 */
val lastBreath: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Last Breath",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_POWER_2_OR_LESS)
        override val resolution =
            ResolutionEffect { state, context ->
                val target = targetedPermanent(context.targets, "Last Breath")
                // CR 608.2h: capture the controller while the creature is still on the battlefield.
                val controller = controllerOfTargetedPermanent(state, target, "Last Breath")
                val exiled = exilePermanent(state, target)
                gainLife(exiled, controller, LAST_BREATH_LIFEGAIN)
            }
    }

/**
 * The single permanent [targets] names (CR 115.1b), for a resolution whose spec is a
 * [TargetSpec.TargetPermanent]. Fails loudly on any other shape: the CR 608.2b re-check has already
 * run, so a resolving removal spell always holds exactly one legal permanent target (ADR-005) and
 * anything else is an engine defect rather than a rules case.
 *
 * `internal` rather than file-private since `FW-MODAL`: the Blasts' destroy and bounce modes ask the
 * same question of the same spec, and two copies of this helper would be two places for "what does a
 * `TargetPermanent` resolution hold?" to drift apart.
 */
internal fun targetedPermanent(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $cardName targets exactly one permanent, got $targets")

/**
 * The controller of the still-on-the-battlefield permanent [objectId], read as CR 608.2h last-known
 * information *before* an effect removes it — what "that artifact's controller" and "its controller"
 * mean once the permanent is gone. Control is ownership in the MVP pool
 * (docs/design/layer-system.md §4). Fails loudly if the permanent is not on the battlefield, for the
 * same CR 608.2b reason [targetedPermanent] does.
 */
private fun controllerOfTargetedPermanent(
    state: GameState,
    objectId: ObjectId,
    cardName: String,
): PlayerId =
    state.sharedZones.battlefield
        .firstOrNull { it.id == objectId }
        ?.owner
        ?: error("CR 608.2b: $cardName's target $objectId is no longer on the battlefield")
