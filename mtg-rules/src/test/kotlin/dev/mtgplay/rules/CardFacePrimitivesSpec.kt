package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AlternativeFace
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.FaceKind
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.LeaveStackDestination
import dev.mtgplay.rules.engine.castDefinitionOf
import dev.mtgplay.rules.engine.castingPermissionsOf
import dev.mtgplay.rules.engine.faceKindOf
import dev.mtgplay.rules.engine.faceNameOf
import dev.mtgplay.rules.engine.markAdventureExile
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.putSpellOffStack
import dev.mtgplay.rules.engine.spellCharacteristics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * `W10-B`'s two-faces framework at rules level, on fixtures (`mtg-rules` names no real card): the
 * CR 715.3b / CR 720.3b **substitution** seam, the synthesized permission, and the three ways a spell
 * can leave the stack.
 *
 * The happy paths — an Adventure that sweeps and then waits in exile, an Omen that finds a land and
 * shuffles itself back — are driven on the real cards in the acceptance module's
 * `TwoFacedCardAcceptanceSpec`. What lives here is what an end-to-end game does not reach: the
 * **counter and fizzle** paths, which CR 715.3d and CR 720.3d deliberately do not touch, and the loud
 * failures a malformed declaration must produce rather than approximate.
 */
class CardFacePrimitivesSpec :
    StringSpec({

        // ---- the substitution seam (CR 715.3a-b, CR 720.3a-b) ---------------------------------------

        "CR 715.3b: a cast via an Adventure permission runs against the face's definition, not the card's" {
            val state = emptyState()
            castDefinitionOf(state, CardRef(ADVENTURER), null) shouldBe fixtureAdventurer
            castDefinitionOf(state, CardRef(ADVENTURER), adventurePermission) shouldBe fixtureAdventureFace
        }

        "CR 715.3b: the spell on the stack has only the face's characteristics — name, cost and card type" {
            // The whole framework in one assertion: a *creature* card cast as its face is a sorcery with a
            // different name and a different cost, which is what makes the permanent-spell test, the
            // counter predicates and the resolution fold all answer correctly with no edit of their own.
            val faced = stackStateFor(fixtureAdventureFace, adventurePermission)
            spellCharacteristics(faced, faced.spellEntry()).let {
                it.name shouldBe ADVENTURE_FACE
                it.manaCost?.render() shouldBe "{1}{R}"
                it.cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }

            val normal = stackStateFor(fixtureAdventurer, castVia = null)
            spellCharacteristics(normal, normal.spellEntry()).let {
                it.name shouldBe ADVENTURER
                it.manaCost?.render() shouldBe "{5}{R}{R}"
                it.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            }
        }

        "ADR-005: a permission naming a face the card does not print fails loudly rather than guessing" {
            // Only `castingPermissionsOf` can synthesize one, so reaching here is an engine defect: the
            // alternatives are to cast the card's own half under the face's cost, or to crash later.
            shouldThrow<IllegalStateException> {
                castDefinitionOf(emptyState(), CardRef(PLAIN_CREATURE), adventurePermission)
            }
            shouldThrow<IllegalArgumentException> {
                castDefinitionOf(emptyState(), CardRef(OMEN_CARD), adventurePermission)
            }
        }

        // ---- the synthesized permission -------------------------------------------------------------

        "CR 715.3: the engine synthesizes exactly one permission per declared face, from the face itself" {
            // The card declares the face once and never the permission, so the cost and the name a seat
            // is offered cannot drift from the definition the cast then runs against.
            castingPermissionsOf(fixtureAdventurer) shouldBe
                listOf(CastingPermission.Adventure(ManaCost.parse("{1}{R}"), ADVENTURE_FACE))
            castingPermissionsOf(fixtureOmenCard) shouldBe
                listOf(CastingPermission.Omen(ManaCost.parse("{G}"), OMEN_FACE))
            // A single-faced card is untouched: the list is exactly what it declares.
            castingPermissionsOf(fixturePlainCreature).shouldBeEmpty()
        }

        "CR 601.2a: a face permission is cast from the hand, and is the caster's choice at CR 715.3" {
            castingPermissionsOf(fixtureAdventurer).single().source shouldBe CastSource.HAND
            castingPermissionsOf(fixtureOmenCard).single().source shouldBe CastSource.HAND
        }

        "the face tests answer for both kinds and for nothing else" {
            listOf(adventurePermission, omenPermission).map { faceKindOf(it) } shouldContainExactlyInAnyOrder
                listOf(FaceKind.ADVENTURE, FaceKind.OMEN)
            faceKindOf(null) shouldBe null
            faceKindOf(CastingPermission.Rebound) shouldBe null
            faceNameOf(adventurePermission) shouldBe ADVENTURE_FACE
            faceNameOf(CastingPermission.Rebound) shouldBe null
        }

        // ---- leaving the stack (CR 608.2m, CR 715.3d, CR 720.3d) ------------------------------------

        "CR 715.3d: an Adventure that is countered or fizzles goes to the graveyard, not to exile" {
            // The rule replaces the move made *as the spell resolves* — the same distinction rebound
            // draws (CR 702.88a) and the reason the destination is decided by the resolution caller
            // rather than by the permission. This is the counter path: no forced destination at all.
            val state = stackStateFor(fixtureAdventureFace, adventurePermission)
            val left = putSpellOffStack(state, state.spellEntry())
            left.destination shouldBe LeaveStackDestination.OWNERS_GRAVEYARD
            left.exiled shouldBe false
            left.state.sharedZones.exile
                .shouldBeEmpty()
            left.state
                .player(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef(ADVENTURER))
        }

        "CR 720.3d: an Omen that is countered or fizzles goes to the graveyard, not into the library" {
            val state = stackStateFor(fixtureOmenFace, omenPermission)
            val left = putSpellOffStack(state, state.spellEntry())
            left.destination shouldBe LeaveStackDestination.OWNERS_GRAVEYARD
            left.state
                .player(alice)
                .library
                .shouldBeEmpty()
            left.state
                .player(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef(OMEN_CARD))
        }

        "CR 720.3d, ADR-006: the shuffle-in puts the card in the library and consumes match entropy" {
            val state = stackStateFor(fixtureOmenFace, omenPermission, library = listOf(FOREST, FOREST))
            val left = putSpellOffStack(state, state.spellEntry(), LeaveStackDestination.OWNERS_LIBRARY)
            left.destination shouldBe LeaveStackDestination.OWNERS_LIBRARY
            left.exiled shouldBe false
            left.state
                .player(alice)
                .graveyard
                .shouldBeEmpty()
            left.state
                .player(alice)
                .library
                .map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef(FOREST), CardRef(FOREST), CardRef(OMEN_CARD))
            // The randomisation drew from the match PRNG and nowhere else: the generator moved.
            (left.state.rng == state.rng) shouldBe false
        }

        "ADR-006: the shuffle-in is a pure function of the seed — the same state replays the same library" {
            val state = stackStateFor(fixtureOmenFace, omenPermission, library = listOf(FOREST, FOREST, FOREST))
            val orders =
                List(2) {
                    putSpellOffStack(state, state.spellEntry(), LeaveStackDestination.OWNERS_LIBRARY)
                        .state
                        .player(alice)
                        .library
                        .map { card -> card.card }
                }
            orders[0] shouldBe orders[1]
        }

        // ---- the adventure exile marker (CR 715.3d) -------------------------------------------------

        "CR 715.3d: the marker rides on the exile object and nothing else changes" {
            val exiled = GameObject(ObjectId(77), CardRef(ADVENTURER), alice)
            val state =
                emptyState().copy(
                    sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf(exiled)),
                )
            val marked = markAdventureExile(state, exiled.id)
            marked.sharedZones.exile
                .single()
                .onAnAdventure shouldBe true
            marked.sharedZones.exile.single() shouldBe exiled.copy(onAnAdventure = true)
        }

        "the marker refuses an id that is not in exile — only a resolving Adventure may set it" {
            shouldThrow<IllegalArgumentException> { markAdventureExile(emptyState(), ObjectId(404)) }
        }

        // ---- the declaration's own guards (CR 205.3k) ------------------------------------------------

        "CR 205.3k: a face that is not an instant or sorcery is refused at declaration" {
            shouldThrow<IllegalArgumentException> { AlternativeFace(FaceKind.ADVENTURE, fixturePlainCreature) }
        }

        "CR 205.3k: a face without the matching spell type is refused, so the two rules cannot be swapped" {
            shouldThrow<IllegalArgumentException> { AlternativeFace(FaceKind.OMEN, fixtureAdventureFace) }
            shouldThrow<IllegalArgumentException> { AlternativeFace(FaceKind.ADVENTURE, fixtureOmenFace) }
        }

        "CR 715.2: a face cannot carry a face of its own" {
            shouldThrow<IllegalArgumentException> { AlternativeFace(FaceKind.ADVENTURE, fixtureAdventurer) }
        }
    })

// ---- names ---------------------------------------------------------------------------------------------

private const val ADVENTURER: String = "Fixture Adventurer"
private const val ADVENTURE_FACE: String = "Fixture Sweep"
private const val OMEN_CARD: String = "Fixture Omen Bearer"
private const val OMEN_FACE: String = "Fixture Wilds"
private const val PLAIN_CREATURE: String = "Fixture Vanilla"
private const val FOREST: String = "Fixture Forest"

private const val FIXTURE_POWER: Int = 6
private const val FIXTURE_TOUGHNESS: Int = 3

// ---- fixtures ------------------------------------------------------------------------------------------

private fun sorceryFace(
    name: String,
    cost: String,
    spellType: String,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(Subtype(spellType)),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** The Adventure half: `{1}{R}` Sorcery — Adventure (CR 205.3k, CR 715.2). */
private val fixtureAdventureFace: SpellDefinition = sorceryFace(ADVENTURE_FACE, "{1}{R}", "Adventure")

/** The Omen half: `{G}` Sorcery — Omen (CR 205.3k, CR 720.2). */
private val fixtureOmenFace: SpellDefinition = sorceryFace(OMEN_FACE, "{G}", "Omen")

private fun creature(
    name: String,
    cost: String,
    face: AlternativeFace?,
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse(cost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(FIXTURE_POWER, FIXTURE_TOUGHNESS),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val alternativeFace = face
    }

/** An adventurer card (CR 715.1): a creature whose inset frame is [fixtureAdventureFace]. */
private val fixtureAdventurer: SpellDefinition =
    creature(ADVENTURER, "{5}{R}{R}", AlternativeFace(FaceKind.ADVENTURE, fixtureAdventureFace))

/** An omen card (CR 720.1): a creature whose inset frame is [fixtureOmenFace]. */
private val fixtureOmenCard: SpellDefinition =
    creature(OMEN_CARD, "{4}{G}", AlternativeFace(FaceKind.OMEN, fixtureOmenFace))

/** The same body with no inset frame — the card a stray face permission must fail loudly against. */
private val fixturePlainCreature: SpellDefinition = creature(PLAIN_CREATURE, "{2}{R}", face = null)

private val adventurePermission: CastingPermission =
    CastingPermission.Adventure(ManaCost.parse("{1}{R}"), ADVENTURE_FACE)

private val omenPermission: CastingPermission = CastingPermission.Omen(ManaCost.parse("{G}"), OMEN_FACE)

private val registry: Map<CardRef, CardDefinition> =
    listOf(fixtureAdventurer, fixtureOmenCard, fixturePlainCreature, fixtureForest)
        .associateBy { CardRef(it.characteristics.name) }

// ---- states --------------------------------------------------------------------------------------------

private fun seat(library: List<GameObject> = emptyList()) =
    PlayerState(
        life = STARTING_LIFE,
        library = library.toPersistentList(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = PriorityStatus.NONE,
    )

private fun stateWith(
    stack: List<StackEntry> = emptyList(),
    library: List<GameObject> = emptyList(),
): GameState =
    GameState(
        players = persistentMapOf(alice to seat(library), bob to seat()),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), stack.toPersistentList(), persistentListOf()),
        nextObjectId = 500,
        rng = Rng(11),
        events = persistentListOf(),
        definitions = registry.toPersistentMap(),
    )

private fun emptyState(): GameState = stateWith()

/**
 * A state whose stack holds one spell of the card [definition] belongs to, cast via [castVia] — the
 * face's definition on the entry and the *card's* ref on the object, which is CR 715.2c's "one card".
 */
private fun stackStateFor(
    definition: SpellDefinition,
    castVia: CastingPermission?,
    library: List<String> = emptyList(),
): GameState {
    val card =
        when (castVia) {
            is CastingPermission.Omen -> OMEN_CARD
            else -> ADVENTURER
        }
    return stateWith(
        stack =
            listOf(
                StackEntry.Spell(
                    obj = GameObject(ObjectId(10), CardRef(card), alice),
                    controller = alice,
                    targets = persistentListOf(),
                    definition = definition,
                    castVia = castVia,
                ),
            ),
        library = library.mapIndexed { index, name -> GameObject(ObjectId(200L + index), CardRef(name), alice) },
    )
}

private fun GameState.spellEntry(): StackEntry.Spell = sharedZones.stack.filterIsInstance<StackEntry.Spell>().single()
