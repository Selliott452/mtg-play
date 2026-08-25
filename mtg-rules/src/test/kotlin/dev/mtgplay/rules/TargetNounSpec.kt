package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ManaValueBound
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.flickerPermanents
import dev.mtgplay.rules.effect.millUntil
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The target nouns and effect primitives `P-ABILSOURCE` adds beside its engine change:
 * [PermanentRestriction.LAND] (Raze), [PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL]
 * (Ghostly Flicker), [SpellRestriction.OfManaValueAtMost] (Spellstutter Sprite), `millUntil`
 * (Balustrade Spy) and `flickerPermanents` (Ghostly Flicker again).
 *
 * Each is pinned at the **enumeration**, not at the card, because that is where legality is defined
 * (ADR-005): the option list an agent sees, the CR 601.2c/603.3d choice and the CR 608.2b re-check are
 * one function, so a test of the enumeration is a test of all three.
 */
class TargetNounSpec :
    StringSpec({

        // ---- PermanentRestriction.LAND (CR 305) ----

        "CR 305: 'target land' enumerates every land on the battlefield, whoever controls it" {
            val state =
                nounState(
                    persistentListOf(
                        GameObject(ObjectId(0), BEAR, alice),
                        GameObject(ObjectId(1), FOREST, alice),
                        GameObject(ObjectId(2), FOREST, bob),
                        GameObject(ObjectId(3), TALISMAN, bob),
                    ),
                )

            val spec = TargetSpec.TargetPermanent(PermanentRestriction.LAND)
            // Raze may destroy its caster's own land as readily as the opponent's.
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
        }

        "CR 205.1a: an artifact land satisfies 'target land' and 'target artifact' both" {
            val state = nounState(persistentListOf(GameObject(ObjectId(0), SEAT_OF_THE_SYNOD, alice)))

            val land = TargetSpec.TargetPermanent(PermanentRestriction.LAND)
            val artifact = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
            // A permanent has *every* card type printed on it, so this is not an either/or.
            legalTargets(state, land, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
            legalTargets(state, artifact, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)))
        }

        // ---- PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL (CR 115.1b, CR 109.5) ----

        "CR 115.1b + CR 109.5: the Ghostly Flicker noun is a union, and is decider-relative" {
            val state =
                nounState(
                    persistentListOf(
                        GameObject(ObjectId(0), BEAR, alice),
                        GameObject(ObjectId(1), FOREST, alice),
                        GameObject(ObjectId(2), TALISMAN, alice),
                        // An enchantment is none of the three, so it is never offered…
                        GameObject(ObjectId(3), WARD_AURA, alice),
                        // …and an opponent's permanents are excluded by "you control".
                        GameObject(ObjectId(4), BEAR, bob),
                    ),
                )

            val spec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
            // The mirror seat sees only its own, which is what makes the restriction decider-relative.
            legalTargets(state, spec, bob, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(4)))
        }

        "CR 205.1a: a permanent that is two of the three noun's types is offered exactly once" {
            // An artifact *land*: the enumeration maps over the battlefield rather than over the noun,
            // which is what keeps the option list duplicate-free and CR 601.2c's distinctness sound.
            val state = nounState(persistentListOf(GameObject(ObjectId(0), SEAT_OF_THE_SYNOD, alice)))

            val spec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
        }

        "CR 601.2c: Ghostly Flicker's 'exactly two' is not castable against a single permanent" {
            val state = nounState(persistentListOf(GameObject(ObjectId(0), BEAR, alice)))

            val spec =
                TargetSpec.TargetPermanent(
                    restriction = PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
                    count = TargetCount.Exactly(2),
                )
            // One legal option, a minimum of two: the enumeration is short of the spec's minimum, which
            // is exactly what `targetsAvailable` reads to keep the cast out of the action list.
            legalTargets(state, spec, alice, Chooser.Nobody).size shouldBe 1
            spec.count.minimum shouldBe 2
        }

        // ---- SpellRestriction.OfManaValueAtMost (CR 202.3) ----

        "CR 202.3: 'mana value X or less' with a fixed bound admits the boundary and excludes above it" {
            val state = nounState(persistentListOf(), stack = persistentListOf(spellOf(ObjectId(9), TWO_DROP)))

            val two = spellOnStackAtMost(ManaValueBound.Fixed(2))
            val one = spellOnStackAtMost(ManaValueBound.Fixed(1))
            // "2 or less" is inclusive; "1 or less" is not.
            legalTargets(state, two, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(9)))
            legalTargets(state, one, alice, Chooser.Nobody).shouldBeEmpty()
        }

        "CR 109.5: Spellstutter Sprite's X counts *your* Faeries, so the two seats see different options" {
            val state =
                nounState(
                    // alice has one Faerie, bob has none.
                    persistentListOf(GameObject(ObjectId(0), FAERIE, alice)),
                    stack = persistentListOf(spellOf(ObjectId(9), ONE_DROP)),
                )

            val spec = spellOnStackAtMost(faeriesYouControl)
            // X = 1 for alice: the one-drop is in reach.
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(9)))
            // X = 0 for bob: nothing is.
            legalTargets(state, spec, bob, Chooser.Nobody).shouldBeEmpty()
        }

        "CR 603.6a: the Sprite counts itself, so a lone Sprite still reaches a one-drop" {
            // The enters-the-battlefield trigger fires *after* the Sprite has entered, so it is on the
            // battlefield and in its own count. This is the single most-misplayed thing about the card.
            val state =
                nounState(
                    persistentListOf(GameObject(ObjectId(0), FAERIE, alice)),
                    stack = persistentListOf(spellOf(ObjectId(9), ONE_DROP)),
                )

            legalTargets(state, spellOnStackAtMost(faeriesYouControl), alice, Chooser.Ability(FAERIE))
                .shouldContainExactly(listOf(Target.SpellOnStack(ObjectId(9))))
        }

        "CR 608.2b: a shrinking X makes an already-chosen target illegal at the re-check" {
            val withTwo =
                nounState(
                    persistentListOf(GameObject(ObjectId(0), FAERIE, alice), GameObject(ObjectId(1), FAERIE, alice)),
                    stack = persistentListOf(spellOf(ObjectId(9), TWO_DROP)),
                )
            val spec = spellOnStackAtMost(faeriesYouControl)
            // X = 2 at the CR 603.3d choice: the two-drop is a legal target…
            legalTargets(withTwo, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.SpellOnStack(ObjectId(9)))

            // …and a Faerie dying in response drops X to 1, which un-targets it. The bound is re-read
            // at the re-check rather than captured at the choice, which is the whole reason this works.
            val withOne =
                nounState(
                    persistentListOf(GameObject(ObjectId(0), FAERIE, alice)),
                    stack = persistentListOf(spellOf(ObjectId(9), TWO_DROP)),
                )
            legalTargets(withOne, spec, alice, Chooser.Nobody).shouldBeEmpty()
        }

        // ---- millUntil (CR 701.13a, CR 701.15a) ----

        "CR 701.15a: the reveal stops at the first land and mills that land too" {
            val state = nounState(persistentListOf(), library = listOf(BEAR, TALISMAN, FOREST, BEAR, BEAR))

            val milled = millUntil(state, alice) { it == FOREST }

            // "…then puts *those* cards into their graveyard" — the whole revealed run, land included.
            milled.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(BEAR, TALISMAN, FOREST)
            milled.players
                .getValue(alice)
                .library
                .map { it.card } shouldContainExactly listOf(BEAR, BEAR)
            // One reveal event for the run, top-first, emitted before anything moved.
            milled.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards shouldContainExactly
                listOf(BEAR, TALISMAN, FOREST)
        }

        "CR 704.5b: a library with no matching card is milled entirely and loses nobody the game" {
            // Spy Combo's whole plan: a deck with no land reveals its entire library.
            val state = nounState(persistentListOf(), library = listOf(BEAR, TALISMAN, BEAR))

            val milled = millUntil(state, alice) { it == FOREST }

            milled.players
                .getValue(alice)
                .library
                .shouldBeEmpty()
            milled.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(BEAR, TALISMAN, BEAR)
            // Milling is not drawing (CR 121.1): no draw was attempted, so no CR 704.5c loss is pending.
            milled.players.getValue(alice).attemptedDrawFromEmptyLibrary shouldBe false
        }

        "CR 701.13a: an already-empty library mills nothing and emits nothing" {
            val state = nounState(persistentListOf(), library = emptyList())

            val milled = millUntil(state, alice) { it == FOREST }

            milled.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            milled.events.shouldBeEmpty()
        }

        "CR 701.15a: a land on top stops the run immediately, milling exactly one card" {
            val state = nounState(persistentListOf(), library = listOf(FOREST, BEAR, BEAR))

            val milled = millUntil(state, alice) { it == FOREST }

            milled.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(FOREST)
        }

        // ---- flickerPermanents (CR 701.3a, CR 400.7) ----

        "CR 400.7: flickering two permanents exiles both before returning either" {
            val state =
                nounState(
                    persistentListOf(
                        GameObject(ObjectId(0), BEAR, alice),
                        GameObject(ObjectId(1), FOREST, alice),
                    ),
                )

            val flickered = flickerPermanents(state, listOf(ObjectId(0), ObjectId(1)))

            // Both come back, as new objects (CR 400.7) — no id survives the round trip.
            flickered.sharedZones.battlefield.map { it.card } shouldContainExactly listOf(BEAR, FOREST)
            flickered.sharedZones.battlefield.none { it.id == ObjectId(0) || it.id == ObjectId(1) } shouldBe true
            flickered.sharedZones.exile.shouldBeEmpty()

            // The simultaneity, stated as an event order: *both* departures precede *both* entries.
            // A fold of the one-object flicker would interleave them, and every CR 603.6a/603.6c
            // trigger detected in between would see the wrong board.
            val moves =
                flickered.events.mapNotNull {
                    when (it) {
                        is GameEvent.PermanentExiled -> "exile"
                        is GameEvent.PermanentEntered -> "enter"
                        else -> null
                    }
                }
            moves shouldContainExactly listOf("exile", "exile", "enter", "enter")
        }

        "CR 601.2c: flickering the same permanent twice is an engine defect, not a rules case" {
            val state = nounState(persistentListOf(GameObject(ObjectId(0), BEAR, alice)))

            shouldThrow<IllegalArgumentException> {
                flickerPermanents(state, listOf(ObjectId(0), ObjectId(0)))
            }
        }
    })

// ---- fixtures ----

private val BEAR = CardRef("Noun Bear")
private val FOREST = CardRef("Noun Forest")
private val TALISMAN = CardRef("Noun Talisman")
private val SEAT_OF_THE_SYNOD = CardRef("Noun Artifact Land")
private val WARD_AURA = CardRef("Noun Enchantment")
private val FAERIE = CardRef("Noun Faerie")
private val ONE_DROP = CardRef("Noun One Drop")
private val TWO_DROP = CardRef("Noun Two Drop")

/** "…with mana value X or less", for whatever [bound] X is. */
private fun spellOnStackAtMost(bound: ManaValueBound) =
    TargetSpec.SpellOnStack(SpellRestriction.OfManaValueAtMost(bound))

/** Spellstutter Sprite's X: the number of Faeries the deciding player controls (CR 109.5). */
private val faeriesYouControl: ManaValueBound =
    ManaValueBound.PerMatching(CountScope.BATTLEFIELD_YOU_CONTROL, ObjectPredicate.HasSubtype(Subtype("Faerie")))

/** A permanent fixture with the given card types and subtypes, and no ability whatever. */
private fun permanentFixture(
    name: String,
    cardTypes: Set<CardType>,
    subtypes: Set<Subtype> = emptySet(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = cardTypes.toPersistentList().let { persistentSetOf(*it.toTypedArray()) },
                subtypes = subtypes.toPersistentList().let { persistentSetOf(*it.toTypedArray()) },
                powerToughness =
                    if (CardType.CREATURE in cardTypes) PrintedPowerToughness(1, 1) else null,
            )
    }

/** An instant fixture of the given mana cost, for the CR 202.3 mana-value tests. */
private fun instantFixture(
    name: String,
    cost: String,
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
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

private val nounDefinitions =
    listOf(
        permanentFixture("Noun Bear", setOf(CardType.CREATURE)),
        permanentFixture("Noun Forest", setOf(CardType.LAND)),
        permanentFixture("Noun Talisman", setOf(CardType.ARTIFACT)),
        permanentFixture("Noun Artifact Land", setOf(CardType.ARTIFACT, CardType.LAND)),
        permanentFixture("Noun Enchantment", setOf(CardType.ENCHANTMENT)),
        permanentFixture("Noun Faerie", setOf(CardType.CREATURE), setOf(Subtype("Faerie"))),
        instantFixture("Noun One Drop", "{U}"),
        instantFixture("Noun Two Drop", "{1}{U}"),
    ).associateBy { CardRef(it.characteristics.name) }
        .toPersistentMap()

/** A spell on the stack under [card], controlled by alice. */
private fun spellOf(
    id: ObjectId,
    card: CardRef,
): StackEntry.Spell {
    val definition =
        nounDefinitions[card] as? SpellDefinition
            ?: error("fixture $card is not castable")
    return StackEntry.Spell(
        obj = GameObject(id, card, alice),
        controller = alice,
        definition = definition,
        targets = persistentListOf(),
    )
}

/** A precombat-main state over [nounDefinitions] with the given battlefield, stack and alice library. */
private fun nounState(
    battlefield: PersistentList<GameObject>,
    stack: PersistentList<StackEntry> = persistentListOf(),
    library: List<CardRef> = emptyList(),
): GameState {
    val used = (battlefield.map { it.id.value } + stack.mapNotNull { (it as? StackEntry.Spell)?.obj?.id?.value })
    val nextId = (used.maxOrNull() ?: -1L) + 1 + library.size
    val libraryObjects =
        library.mapIndexed { index, card ->
            GameObject(ObjectId((used.maxOrNull() ?: -1L) + 1 + index), card, alice)
        }

    fun seat(cards: List<GameObject>) =
        PlayerState(STARTING_LIFE, cards.toPersistentList(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(libraryObjects), bob to seat(emptyList())),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield = battlefield, stack = stack, exile = persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = nounDefinitions,
    )
}
