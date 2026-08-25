package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.CountCondition
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellCostReduction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCondition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Fixtures for the CR 601.2f cost-modification specs (docs/design/cost-modification.md).
 *
 * `mtg-rules` names no real card (PLAN.md §3), so every shape the framework must handle is a synthetic
 * definition here: a count-based self reduction (affinity's shape), a graveyard-counting reduction (the
 * Terrors' shape), a conditional flat reduction (Of One Mind's shape), a battlefield permanent that
 * reduces other spells (Sunscape Familiar's shape — the card itself cannot ship, see
 * `mtg-cards/CostReductionCards.kt`), and the three cards that make the **lock-in** stages observable:
 * one whose sacrifice cost removes a counted artifact, and one whose additional discard adds to a
 * counted graveyard.
 */

/** The artifact subtype [fixtureRelic] carries; nothing conditions on it, it just needs a body. */
internal val FIXTURE_RELIC_TYPE: Subtype = Subtype("Fixture Relic")

/** The reduction [fixtureScrapper] and [fixtureTithe] declare per matching artifact (CR 702.41a). */
internal val affinityForFixtureArtifacts: CostReduction =
    CostReduction.PerMatching(
        amountPerMatch = 1,
        scope = CountScope.BATTLEFIELD_YOU_CONTROL,
        predicate = ObjectPredicate.HasCardType(CardType.ARTIFACT),
    )

/** The reduction [fixtureLeviathan] declares per instant or sorcery card in the graveyard. */
internal val perGraveyardSpell: CostReduction =
    CostReduction.PerMatching(
        amountPerMatch = 1,
        scope = CountScope.YOUR_GRAVEYARD,
        predicate =
            ObjectPredicate.AnyOf(
                persistentListOf(
                    ObjectPredicate.HasCardType(CardType.INSTANT),
                    ObjectPredicate.HasCardType(CardType.SORCERY),
                ),
            ),
    )

/**
 * "Fixture Relic" — a plain `{1}` artifact with no abilities. The thing the affinity fixtures count,
 * and the thing [fixtureTithe]'s sacrifice cost destroys mid-cast.
 */
internal val fixtureRelic: SpellDefinition =
    permanentFixture(
        name = "Fixture Relic",
        cost = "{1}",
        cardTypes = persistentSetOf(CardType.ARTIFACT),
        subtypes = persistentSetOf(FIXTURE_RELIC_TYPE),
    )

/**
 * "Fixture Scrapper" — a `{5}{U}` artifact creature with affinity for artifacts. The coloured floor of
 * CR 118.7a in fixture form: however many artifacts are out, `{U}` survives.
 */
internal val fixtureScrapper: SpellDefinition =
    object : SpellDefinition by permanentFixture(
        name = "Fixture Scrapper",
        cost = "{5}{U}",
        cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 3, toughness = 3),
    ) {
        override val costReduction = affinityForFixtureArtifacts
    }

/**
 * "Fixture Colossus" — a `{6}` **colourless** artifact creature with affinity. Myr Enforcer's shape,
 * and the only fixture whose cost can reach the CR 601.2f `{0}` floor: with no coloured pip there is
 * nothing for CR 118.7a to protect, so six artifacts reduce it to nothing.
 */
internal val fixtureColossus: SpellDefinition =
    object : SpellDefinition by permanentFixture(
        name = "Fixture Colossus",
        cost = "{6}",
        cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 4, toughness = 4),
    ) {
        override val costReduction = affinityForFixtureArtifacts
    }

/**
 * "Fixture Leviathan" — a `{4}{U}` creature reducing `{1}` per instant or sorcery card in its
 * controller's graveyard. Cryptic Serpent's shape; the fixture the graveyard-count and
 * additional-discard lock-in tests use.
 */
internal val fixtureLeviathan: SpellDefinition =
    object : SpellDefinition by permanentFixture(
        name = "Fixture Leviathan",
        cost = "{4}{U}",
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 5, toughness = 5),
    ) {
        override val costReduction = perGraveyardSpell
    }

/**
 * "Fixture Accord" — a `{3}{U}` sorcery costing `{2}` less while its controller has at least two
 * artifacts. Of One Mind's [CostReduction.IfAll] shape: a flat amount or nothing, never in between,
 * which is what distinguishes it from [fixtureScrapper]'s linear count.
 */
internal val fixtureAccord: SpellDefinition =
    object : SpellDefinition by spellFixtureOf(
        name = "Fixture Accord",
        cost = "{3}{U}",
        cardType = CardType.SORCERY,
    ) {
        override val costReduction =
            CostReduction.IfAll(
                amount = FIXTURE_ACCORD_REDUCTION,
                conditions =
                    persistentListOf(
                        CountCondition(
                            scope = CountScope.BATTLEFIELD_YOU_CONTROL,
                            predicate = ObjectPredicate.HasCardType(CardType.ARTIFACT),
                            atLeast = FIXTURE_ACCORD_THRESHOLD,
                        ),
                    ),
            )
    }

/** What [fixtureAccord] takes off its cost when its condition is met, and nothing otherwise. */
internal const val FIXTURE_ACCORD_REDUCTION: Int = 2

/** How many artifacts [fixtureAccord] demands before it reduces at all. */
internal const val FIXTURE_ACCORD_THRESHOLD: Int = 2

/**
 * "Fixture Warden" — a `{1}{W}` creature whose static ability makes its controller's **blue** spells
 * cost `{1}` less (CR 604.5, CR 613.11). Sunscape Familiar's shape, minus the Defender the real card
 * prints and this engine has no keyword for.
 *
 * White while reducing blue on purpose: the reducer shares no colour with what it reduces, so a test
 * that passed by accidentally matching the reducer's own colour would fail here.
 */
internal val fixtureWarden: CardDefinition =
    object : CardDefinition by permanentFixture(
        name = "Fixture Warden",
        cost = "{1}{W}",
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 0, toughness = 3),
    ) {
        override val spellCostReductions =
            persistentListOf(SpellCostReduction(amount = 1, spellColors = persistentSetOf(Color.BLUE)))
    }

/**
 * "Fixture Tithe" — a `{4}{U}` artifact creature with affinity whose **alternative cost** sacrifices a
 * Fixture Relic (CR 601.2h). The CR 601.2h Altar's-Reap example in fixture form: the sacrifice removes
 * an artifact the affinity count read, so a pipeline that determined the cost *after* the sacrifice
 * stage would charge one more than it enumerated.
 */
internal val fixtureTithe: SpellDefinition =
    object : SpellDefinition by permanentFixture(
        name = "Fixture Tithe",
        cost = "{4}{U}",
        cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
    ) {
        override val costReduction = affinityForFixtureArtifacts
        override val castingPermissions =
            listOf(
                CastingPermission.AlternativeCost(
                    cost = ManaCost.parse("{4}{U}"),
                    sacrifice = SacrificeRequirement(1, FIXTURE_RELIC_TYPE),
                ),
            )
    }

/** A land producing `{U}`, so the fixture boards can pay a blue pip. */
internal val fixtureAtoll: CardDefinition = manaLandFixture("Fixture Atoll", ManaType.BLUE)

/** A land producing `{C}`, the generic filler the reduction tests count symbols against. */
internal val fixtureWaste: CardDefinition = manaLandFixture("Fixture Waste", ManaType.COLORLESS)

/** "Fixture Rite" — a plain `{1}` sorcery: graveyard filler that the Terrors' clause counts. */
internal val fixtureRite: SpellDefinition =
    spellFixtureOf(name = "Fixture Rite", cost = "{1}", cardType = CardType.SORCERY)

/** "Fixture Spark" — a plain `{1}` instant: the other half of the counted graveyard. */
internal val fixtureSpark: SpellDefinition =
    spellFixtureOf(name = "Fixture Spark", cost = "{1}", cardType = CardType.INSTANT)

/** "Fixture Stone" — a plain `{1}` enchantment: graveyard filler the clause must **not** count. */
internal val fixtureStone: SpellDefinition =
    spellFixtureOf(name = "Fixture Stone", cost = "{1}", cardType = CardType.ENCHANTMENT)

/**
 * "Fixture Reckoning" — a `{4}{U}` sorcery with the graveyard-count reduction **and** an intrinsic
 * additional discard cost (CR 601.2b, Grab the Prize's shape).
 *
 * The fixture that makes the discard stage's lock-in observable: the discarded card lands in the very
 * graveyard the reduction counts, so a pipeline that determined the cost after the discard would price
 * the spell one cheaper than the plan it had already offered.
 */
internal val fixtureReckoning: SpellDefinition =
    object : SpellDefinition by spellFixtureOf(
        name = "Fixture Reckoning",
        cost = "{4}{U}",
        cardType = CardType.SORCERY,
    ) {
        override val costReduction = perGraveyardSpell
        override val additionalCost = AdditionalCost.DiscardCards(1)
    }

/**
 * "Fixture Recall" — a `{3}{U}` sorcery with the graveyard-count reduction and **flashback** from the
 * graveyard at the same cost (CR 702.34).
 *
 * The only fixture whose gathering-time and execution-time reads could differ: cast from the graveyard,
 * the card is sitting in the zone its own reduction counts while the request is derived, and has left
 * it by the time the pipeline recomputes. Excluding the cast object is what makes the two equal.
 */
internal val fixtureRecall: SpellDefinition =
    object : SpellDefinition by spellFixtureOf(
        name = "Fixture Recall",
        cost = "{3}{U}",
        cardType = CardType.SORCERY,
    ) {
        override val costReduction = perGraveyardSpell
        override val castingPermissions =
            listOf(CastingPermission.Flashback(cost = ManaCost.parse("{3}{U}")))
    }

/**
 * "Fixture Lasso" — a `{4}{U}` instant that costs `{3}` less while it targets a **tapped** permanent
 * (CR 601.2f). Ride's End's shape, and the only fixture whose cost is a function of a *choice* rather
 * than of the board.
 *
 * It targets plain "target creature" rather than the real card's creature-or-Vehicle, because the
 * targeting noun is not what this framework is about: what matters is that the set the caster picks
 * from and the set that discounts the spell are **different** sets on the same card, so an untapped
 * creature is a legal target that prices the cast at five.
 */
internal val fixtureLasso: SpellDefinition =
    object : SpellDefinition by spellFixtureOf(
        name = "Fixture Lasso",
        cost = "{4}{U}",
        cardType = CardType.INSTANT,
    ) {
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val costReduction =
            CostReduction.IfTargets(
                amount = FIXTURE_LASSO_REDUCTION,
                condition = TargetCondition.TAPPED_PERMANENT,
            )
    }

/** What [fixtureLasso] takes off its cost when its chosen target is tapped, and nothing otherwise. */
internal const val FIXTURE_LASSO_REDUCTION: Int = 3

/** "Fixture Ox" — a plain 2/2 creature body: the thing [fixtureLasso] points at, tapped or not. */
internal val fixtureOx: SpellDefinition =
    permanentFixture(
        name = "Fixture Ox",
        cost = "{2}",
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = persistentSetOf(),
        powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
    )

/** The registry the cost-modification specs run against: the shared fixtures plus this file's. */
internal val costFixtureDefinitions: Map<CardRef, CardDefinition> =
    fixtureDefinitions +
        listOf(
            fixtureRelic,
            fixtureScrapper,
            fixtureColossus,
            fixtureLeviathan,
            fixtureAccord,
            fixtureWarden,
            fixtureTithe,
            fixtureAtoll,
            fixtureWaste,
            fixtureRite,
            fixtureSpark,
            fixtureStone,
            fixtureReckoning,
            fixtureRecall,
            fixtureLasso,
            fixtureOx,
        ).associateBy { CardRef(it.characteristics.name) }

/** A permanent spell fixture: sorcery-speed, untargeted, resolving onto the battlefield (CR 608.3). */
private fun permanentFixture(
    name: String,
    cost: String,
    cardTypes: PersistentSet<CardType>,
    subtypes: PersistentSet<Subtype>,
    powerToughness: PrintedPowerToughness? = null,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = cardTypes,
                subtypes = subtypes,
                powerToughness = powerToughness,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** A non-permanent spell fixture with no resolution instructions — only its cost is under test. */
private fun spellFixtureOf(
    name: String,
    cost: String,
    cardType: CardType,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(cardType),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing =
            if (cardType == CardType.INSTANT) TimingClass.INSTANT_SPEED else TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** A land whose one mana ability adds [type]. */
private fun manaLandFixture(
    name: String,
    type: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities =
            persistentListOf(ManaAbility(persistentListOf(type)))
    }
