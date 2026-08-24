package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ReplacementEffect
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.loseLife
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Fixture cards for the P5.2 cast-from-elsewhere and replacement frameworks (the rules module names no
 * real card). No real madness/flashback card lands this packet — Fiery Temper, Lava Dart, and Faithless
 * Looting are the P6 Madness deck — so these fixtures stand in, each cost shape the frameworks must
 * cover: madness (discard→exile replacement plus a reflexive cast from exile), flashback (cast from
 * graveyard, exiled as it leaves the stack), and escape (cast from graveyard with an additional
 * exile-N-others cost).
 */

/** Resolution: the single targeted player loses [amount] life. */
private fun targetPlayerLosesLife(amount: Int): ResolutionEffect =
    ResolutionEffect { state, context ->
        when (val target = context.targets.single()) {
            is Target.Player -> loseLife(state, target.id, amount)
            is Target.Permanent -> error("fixture unexpectedly targeted a permanent: $target")
            is Target.SpellOnStack -> error("fixture unexpectedly targeted a spell on the stack: $target")
        }
    }

/** Resolution: the spell's controller loses [amount] life (no target). */
private fun controllerLosesLife(amount: Int): ResolutionEffect =
    ResolutionEffect { state, context -> loseLife(state, context.controller, amount) }

/** The cast-from-elsewhere data a fixture carries: its permissions and its self-watching replacements. */
private data class Extras(
    val permissions: List<CastingPermission> = emptyList(),
    val replacements: List<ReplacementEffect> = emptyList(),
)

/** An instant fixture (every cast-from-elsewhere fixture is an instant) with the given cost and extras. */
private fun spellFixture(
    name: String,
    cost: String,
    spec: TargetSpec,
    effect: ResolutionEffect,
    extras: Extras,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = spec
        override val resolution = effect
        override val castingPermissions = extras.permissions
        override val replacementEffects = extras.replacements.toPersistentList()
    }

/** What a Fixture Fiery Temper deals to its targeted player (CR 119.3c via the lose-life primitive). */
internal const val FIXTURE_FIERY_TEMPER_LIFE_LOSS: Int = 3

/**
 * "Fixture Fiery Temper" — `{1}{R}` instant, any target loses 3 life; **madness `{R}`**. The archetypal
 * madness card (Fiery Temper's shape): a discard→exile replacement (CR 702.35a) plus a reflexive cast
 * from exile for `{R}` (CR 702.35b).
 */
internal val fixtureFieryTemper: SpellDefinition =
    spellFixture(
        name = "Fixture Fiery Temper",
        cost = "{1}{R}",
        spec = TargetSpec.AnyTarget,
        effect = targetPlayerLosesLife(FIXTURE_FIERY_TEMPER_LIFE_LOSS),
        extras =
            Extras(
                permissions = listOf(CastingPermission.Madness(ManaCost.parse("{R}"))),
                replacements = listOf(ReplacementEffect.DiscardToExileInstead),
            ),
    )

/**
 * "Fixture Double Madness" — a `{R}` instant with **two** discard→exile replacements and a madness
 * permission. No real card carries two discard replacements; this exercises the CR 616.1 ordering
 * choice and CR 614.5 once-per-event re-check.
 */
internal val fixtureDoubleMadness: SpellDefinition =
    spellFixture(
        name = "Fixture Double Madness",
        cost = "{R}",
        spec = TargetSpec.None,
        effect = controllerLosesLife(1),
        extras =
            Extras(
                permissions = listOf(CastingPermission.Madness(ManaCost.parse("{R}"))),
                replacements = listOf(ReplacementEffect.DiscardToExileInstead, ReplacementEffect.DiscardToExileInstead),
            ),
    )

/** What a Fixture Flashback Bolt deals to its targeted player. */
internal const val FIXTURE_FLASHBACK_LIFE_LOSS: Int = 3

/**
 * "Fixture Flashback Bolt" — `{R}` instant, any target loses 3 life; **flashback `{2}{R}`**. Cast from
 * the graveyard for its flashback cost; the flashback spell is exiled instead of going to a graveyard
 * as it leaves the stack (CR 702.34e), on resolution and on a fizzle alike.
 */
internal val fixtureFlashbackBolt: SpellDefinition =
    spellFixture(
        name = "Fixture Flashback Bolt",
        cost = "{R}",
        spec = TargetSpec.AnyTarget,
        effect = targetPlayerLosesLife(FIXTURE_FLASHBACK_LIFE_LOSS),
        extras = Extras(permissions = listOf(CastingPermission.Flashback(ManaCost.parse("{2}{R}")))),
    )

/** Sentinel's Eyes' escape exiles this many other cards; the fixture escape spell mirrors it. */
internal const val FIXTURE_ESCAPE_EXILE: Int = 2

/**
 * "Fixture Escape Bolt" — `{1}{R}` instant, any target loses 3 life; **escape `{R}`, exile two other
 * cards from your graveyard**. Cast from the graveyard for the escape cost plus the additional exile
 * cost; unlike flashback it resolves and leaves the stack by the ordinary rules (no leave-stack
 * replacement). Non-permanent so it exercises escape's cost machinery without the aura enter-attached
 * detail (which Sentinel's Eyes covers in acceptance).
 */
internal val fixtureEscapeBolt: SpellDefinition =
    spellFixture(
        name = "Fixture Escape Bolt",
        cost = "{1}{R}",
        spec = TargetSpec.AnyTarget,
        effect = targetPlayerLosesLife(FIXTURE_FLASHBACK_LIFE_LOSS),
        extras =
            Extras(
                permissions =
                    listOf(CastingPermission.Escape(cost = ManaCost.parse("{R}"), exileOthers = FIXTURE_ESCAPE_EXILE)),
            ),
    )

/** Every cast-from-elsewhere fixture, keyed by ref — for registries alongside [fixtureDefinitions]. */
internal val castFromElsewhereFixtures: Map<CardRef, SpellDefinition> =
    listOf(
        fixtureFieryTemper,
        fixtureDoubleMadness,
        fixtureFlashbackBolt,
        fixtureEscapeBolt,
    ).associateBy { CardRef(it.characteristics.name) }
