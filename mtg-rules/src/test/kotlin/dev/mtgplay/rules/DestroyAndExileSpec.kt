package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/**
 * The removal-and-destruction packet's two effect primitives and the "target &lt;permanent&gt;"
 * enumeration they are chosen with, unit-level: the CR 701.7a destroy, the CR 701.3a exile, and
 * [PermanentRestriction] as read by [legalTargets] (CR 115.1b).
 *
 * The two primitives are pinned *here*, apart from any card, because they are `mtg-rules`
 * vocabulary: the cards that compose them are played end-to-end in `RemovalAcceptanceSpec`. The
 * headline case is CR 702.12b — until this packet, [Keyword.INDESTRUCTIBLE] had no reachable effect
 * on anything, and a destroy that failed to consult it would have looked perfectly correct.
 */
class DestroyAndExileSpec :
    StringSpec({

        "CR 701.7a: destroying a permanent puts it into its owner's graveyard as a new object" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), IDOL, alice)))

            val destroyed = destroy(state, ObjectId(0))

            destroyed.sharedZones.battlefield.shouldBeEmpty()
            val inGraveyard =
                destroyed.players
                    .getValue(alice)
                    .graveyard
                    .single()
            inGraveyard.card shouldBe IDOL
            inGraveyard.owner shouldBe alice
            // CR 400.7: the graveyard object is a *new* object, not the battlefield one.
            inGraveyard.id shouldNotBe ObjectId(0)
            val event = destroyed.events.filterIsInstance<GameEvent.PermanentDestroyed>().single()
            event.objectId shouldBe ObjectId(0)
            event.card shouldBe IDOL
            event.graveyardObjectId shouldBe inGraveyard.id
        }

        "CR 701.7a: a destroyed permanent goes to its *owner's* graveyard, not the destroyer's" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), IDOL, bob)))

            val destroyed = destroy(state, ObjectId(0))

            destroyed.players
                .getValue(bob)
                .graveyard
                .single()
                .card shouldBe IDOL
            destroyed.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 702.12b: a destroy effect does not destroy an indestructible permanent" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), WARDED_IDOL, alice)))

            val destroyed = destroy(state, ObjectId(0))

            // It is still on the battlefield, nothing reached a graveyard, and — crucially — the log
            // carries no destruction event, so "destroyed nothing" is observable.
            destroyed.sharedZones.battlefield
                .single()
                .id shouldBe ObjectId(0)
            destroyed.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            destroyed.events.filterIsInstance<GameEvent.PermanentDestroyed>().shouldBeEmpty()
            destroyed shouldBe state
        }

        "CR 603.6b: a destroyed permanent's put-into-graveyard trigger fires against its pre-destruction state" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), SEEDLING, alice)))

            val destroyed = destroy(state, ObjectId(0))

            val trigger = destroyed.pendingTriggers.single()
            trigger.sourceCard shouldBe SEEDLING
            trigger.controller shouldBe alice
            // CR 603.10: the trigger's subject is the fresh graveyard object it will act on.
            trigger.subject shouldBe
                destroyed.players
                    .getValue(alice)
                    .graveyard
                    .single()
                    .id
        }

        "CR 701.7a: destroying something that is not on the battlefield fails loudly" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), IDOL, alice)))

            shouldThrow<IllegalArgumentException> { destroy(state, ObjectId(9)) }
                .message
                .orEmpty() shouldContain "CR 701.7a"
        }

        "CR 701.3a: exiling a permanent moves it from the battlefield to exile as a new object" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), ENT, bob)))

            val exiled = exilePermanent(state, ObjectId(0))

            exiled.sharedZones.battlefield.shouldBeEmpty()
            val inExile = exiled.sharedZones.exile.single()
            inExile.card shouldBe ENT
            // CR 400.7: a new object, and one carrying no battlefield status memory.
            inExile.id shouldNotBe ObjectId(0)
            inExile.owner shouldBe bob
            inExile.tapped.shouldBeFalse()
            inExile.damageMarked shouldBe 0
            // Nothing reached a graveyard: exiling is not destroying, and not dying.
            exiled.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            val event = exiled.events.filterIsInstance<GameEvent.PermanentExiled>().single()
            event.objectId shouldBe ObjectId(0)
            event.exileObjectId shouldBe inExile.id
        }

        "CR 702.12b: indestructible does not stop an exile — exiling is not destroying" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), WARDED_IDOL, alice)))

            val exiled = exilePermanent(state, ObjectId(0))

            exiled.sharedZones.battlefield.shouldBeEmpty()
            exiled.sharedZones.exile
                .single()
                .card shouldBe WARDED_IDOL
        }

        "CR 603.6b: exiling a permanent fires no put-into-graveyard trigger" {
            val state = removalState(battlefield = persistentListOf(GameObject(ObjectId(0), SEEDLING, alice)))

            // The same permanent whose destruction fires a trigger, exiled instead: nothing fires,
            // because nothing was put into a graveyard.
            exilePermanent(state, ObjectId(0)).pendingTriggers.shouldBeEmpty()
        }

        "CR 115.1b: 'target creature' enumerates every creature on the battlefield and no player" {
            val state =
                removalState(
                    battlefield =
                        persistentListOf(
                            GameObject(ObjectId(0), ENT, alice),
                            GameObject(ObjectId(1), OGRE, bob),
                            GameObject(ObjectId(2), IDOL, bob),
                        ),
                )

            val creatures = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            legalTargets(state, creatures, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)))
        }

        "CR 205.4: 'target nonlegendary creature' offers every creature but the legendary one" {
            val state =
                removalState(
                    battlefield =
                        persistentListOf(
                            GameObject(ObjectId(0), ENT, alice),
                            GameObject(ObjectId(1), OGRE, bob),
                        ),
                )

            val spec = TargetSpec.TargetPermanent(PermanentRestriction.NONLEGENDARY_CREATURE)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
        }

        "CR 301: 'target artifact' enumerates artifacts — an indestructible one included" {
            val state =
                removalState(
                    battlefield =
                        persistentListOf(
                            GameObject(ObjectId(0), ENT, alice),
                            GameObject(ObjectId(1), IDOL, bob),
                            GameObject(ObjectId(2), WARDED_IDOL, bob),
                        ),
                )

            // CR 702.12b restricts destruction, never targeting: an indestructible artifact is a
            // perfectly legal target that the destroy then does nothing to.
            val artifacts = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
            legalTargets(state, artifacts, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
        }

        "CR 115.1b: 'target permanent' enumerates every permanent, land included, and never a player" {
            val state =
                removalState(
                    battlefield =
                        persistentListOf(
                            GameObject(ObjectId(0), ENT, alice),
                            GameObject(ObjectId(1), MEADOW, bob),
                            GameObject(ObjectId(2), IDOL, bob),
                        ),
                )

            val spec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
        }

        "CR 613: 'power 2 or less' reads layered power — an Aura's pump makes the creature illegal" {
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_POWER_2_OR_LESS)
            val bare =
                removalState(
                    battlefield =
                        persistentListOf(GameObject(ObjectId(0), ENT, alice), GameObject(ObjectId(1), OGRE, bob)),
                )

            // The Ent's printed power is 2 (legal); the Ogre's is 3 (illegal).
            legalTargets(bare, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))

            // CR 613 sublayer 7c: a +2/+2 Aura takes the Ent to power 4 and it stops being a legal
            // target — the CR 608.2b re-check a pump-in-response wins with (trap T14). Reading
            // *printed* power here would still offer it, and would be silently wrong.
            val enchanted =
                removalState(
                    battlefield =
                        persistentListOf(
                            GameObject(ObjectId(0), ENT, alice),
                            GameObject(ObjectId(1), OGRE, bob),
                            GameObject(ObjectId(2), CLOAK, alice, attachedTo = ObjectId(0)),
                        ),
                )
            legalTargets(enchanted, spec, alice, Chooser.Nobody).shouldBeEmpty()
        }

        "CR 702.11: hexproof keeps a creature out of an opponent's permanent-target enumeration" {
            val state =
                removalState(
                    battlefield =
                        persistentListOf(GameObject(ObjectId(0), ENT, alice), GameObject(ObjectId(1), SPRITE, bob)),
                )
            val spec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)

            // Alice may not target bob's hexproof Sprite; bob targets his own freely (CR 702.11).
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
            legalTargets(state, spec, bob, Chooser.Nobody) shouldContainExactly
                listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)))
        }
    })

// ---- fixtures --------------------------------------------------------------------------------

private val IDOL = CardRef("Fixture Idol")
private val WARDED_IDOL = CardRef("Fixture Warded Idol")
private val OGRE = CardRef("Fixture Ogre")
private val SPRITE = CardRef("Fixture Sprite")
private val SEEDLING = CardRef("Fixture Seedling")
private val ENT = CardRef(fixtureEnt.characteristics.name)
private val MEADOW = CardRef(fixtureMeadow.characteristics.name)
private val CLOAK = CardRef(fixtureCloak.characteristics.name)

/** A non-creature artifact fixture (CR 301), optionally indestructible (CR 702.12). */
private fun artifactFixture(
    name: String,
    indestructible: Boolean,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
                keywords =
                    if (indestructible) persistentSetOf(Keyword.INDESTRUCTIBLE) else persistentSetOf(),
            )
    }

/** A creature-body fixture with the given supertypes, keywords, and triggered abilities (CR 302). */
private fun creatureFixture(
    name: String,
    power: Int,
    toughness: Int,
    supertypes: Set<Supertype> = emptySet(),
    keywords: Set<Keyword> = emptySet(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = supertypes.toPersistentSet(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
                keywords = keywords.toPersistentSet(),
            )
    }

/** "Fixture Idol" — a plain artifact, the destroy primitive's simplest subject. */
private val fixtureIdol = artifactFixture("Fixture Idol", indestructible = false)

/** "Fixture Warded Idol" — an indestructible artifact (CR 702.12), the CR 702.12b subject. */
private val fixtureWardedIdol = artifactFixture("Fixture Warded Idol", indestructible = true)

/** "Fixture Ogre" — a *legendary* 3/3 (CR 205.4); the pool prints no legendary card of its own. */
private val fixtureOgre = creatureFixture("Fixture Ogre", 3, 3, supertypes = setOf(Supertype.LEGENDARY))

/** "Fixture Sprite" — a 1/1 with hexproof (CR 702.11), for the targeting-restriction case. */
private val fixtureSprite = creatureFixture("Fixture Sprite", 1, 1, keywords = setOf(Keyword.HEXPROOF))

/** "Fixture Seedling" — a 1/1 with a CR 603.6b put-into-graveyard-from-the-battlefield trigger. */
private val fixtureSeedling: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Seedling",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(1, 1),
            )
        override val triggeredAbilities: PersistentList<TriggeredAbility> =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                ),
            )
    }

/** Every definition these specs build states with: the local fixtures plus the shared Aura ones. */
private val removalDefinitions =
    (
        auraDefinitions +
            listOf(fixtureIdol, fixtureWardedIdol, fixtureOgre, fixtureSprite, fixtureSeedling)
                .associateBy { CardRef(it.characteristics.name) }
    ).toPersistentMap()

/** A precombat-main state with no stack and the given battlefield, over [removalDefinitions]. */
private fun removalState(battlefield: PersistentList<GameObject>): GameState {
    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    val nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield = battlefield, stack = persistentListOf(), exile = persistentListOf()),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = removalDefinitions,
    )
}
