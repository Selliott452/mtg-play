package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Inventor's Axe against the oracle card (CR 201–208), and the three rules readings its encoding turns
 * on: two separate enters triggers rather than one, sorcery-timed equip against an instant-speed cast,
 * and a `+2/+0` that is an ordinary layer-7c static over the attached object.
 *
 * The *behaviour* of equip — CR 704.5n, moving an attachment, and the energy cost gating enumeration —
 * is exercised against the real engine in `mtg-rules` (`EquipSpec`), over fixtures.
 */
class EquipmentSpec :
    StringSpec({

        "CR 201-208: Inventor's Axe is a {R} Artifact — Equipment with no P/T box" {
            with(inventorsAxe.characteristics) {
                name shouldBe "Inventor's Axe"
                manaCost shouldBe ManaCost.parse("{R}")
                supertypes.shouldBeEmpty()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                // CR 301.5a: Equipment is an artifact subtype, and it is the only thing that marks one —
                // the CR 704.5n state-based action reads exactly this word.
                subtypes shouldBe persistentSetOf(Subtype("Equipment"))
                powerToughness.shouldBeNull()
                keywords.shouldBeEmpty()
            }
            // CR 702.8a: flash is a timing permission, not a printed keyword.
            inventorsAxe.timing shouldBe TimingClass.INSTANT_SPEED
            inventorsAxe.targetSpec shouldBe TargetSpec.None
        }

        "CR 613.4c: 'equipped creature gets +2/+0' is an ordinary static over the attached object" {
            val static = inventorsAxe.staticContinuousEffects.single()
            // "Equipped creature" and "enchanted creature" are one object relation with two names, so
            // this needs no Equipment-specific affected set.
            static.affects shouldBe AffectedSet.Enchanted
            static.powerMod shouldBe Magnitude.Fixed(2)
            static.toughnessMod shouldBe Magnitude.Zero
            static.grantedKeywords.shouldBeEmpty()
        }

        "CR 603.1: the card prints *two* enters triggers, and only the second one targets" {
            val triggers = inventorsAxe.triggeredAbilities
            triggers.map { it.condition } shouldContainExactly
                listOf(TriggerCondition.EnteredBattlefieldSelf, TriggerCondition.EnteredBattlefieldSelf)
            // Keeping them separate is not pedantry: they go on the stack as two objects, and a board
            // with no creature loses the attach trigger to CR 603.3d while the energy still resolves.
            triggers[0].targetSpec shouldBe TargetSpec.None
            triggers[1].targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)
        }

        "CR 107.16: the first enters trigger gives its controller two energy counters" {
            val state = axeState()
            state.players.getValue(alice).energyCounters shouldBe 0
            val resolved =
                inventorsAxe.triggeredAbilities[0]
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf(), sourceCard = AXE))
            resolved.players.getValue(alice).energyCounters shouldBe 2
            resolved.players.getValue(bob).energyCounters shouldBe 0
        }

        "CR 701.3a: the second enters trigger attaches the Axe, and the host gets +2/+0" {
            val attached = resolveAttach(axeState())
            attached.sharedZones.battlefield
                .single { it.id == AXE_ID }
                .attachedTo shouldBe CREATURE_ID
            // +2/+0 on a 2/2: the toughness half is deliberately untouched, which is what makes the Axe
            // a race card rather than a survival one.
            layeredCharacteristics(attached, CREATURE_ID).power shouldBe 4
            layeredCharacteristics(attached, CREATURE_ID).toughness shouldBe 2
        }

        "CR 702.6b: equip costs {E}{E}, targets a creature you control, and is sorcery-timed" {
            val equip = inventorsAxe.activatedAbilities.single()
            // Energy, not mana: no payment plan is enumerated and no mana source is reserved.
            equip.cost shouldContainExactly listOf(AbilityCost.Energy(2))
            equip.timing shouldBe TimingClass.SORCERY_SPEED
            equip.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)
            // The card's own enters trigger is *not* sorcery-timed — that asymmetry is the card's one
            // real decision, and it is what makes the free attach a combat trick and the re-equip not.
            inventorsAxe.triggeredAbilities[1].targetSpec shouldBe equip.targetSpec
        }

        "CR 603.3d / CR 113.7c: the attach clause is inert with no target and with no source" {
            val state = axeState()
            val noTarget =
                inventorsAxe.triggeredAbilities[1]
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf(), source = AXE_ID, sourceCard = AXE))
            noTarget shouldBe state
            // An ability whose source has left the battlefield still resolves from last-known
            // information (CR 113.7c) — with nothing to attach.
            val noSource =
                inventorsAxe.triggeredAbilities[1]
                    .effect
                    .resolve(
                        state,
                        ResolutionContext(alice, persistentListOf(Target.Permanent(CREATURE_ID)), sourceCard = AXE),
                    )
            noSource shouldBe state
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val STARTING_LIFE: Int = 20

private val AXE = CardRef("Inventor's Axe")
private val AXE_ID = ObjectId(0)
private val CREATURE_ID = ObjectId(1)

/** Resolves the Axe's attach trigger onto the board's one creature (CR 603.6a). */
private fun resolveAttach(state: GameState): GameState =
    inventorsAxe.triggeredAbilities[1]
        .effect
        .resolve(
            state,
            ResolutionContext(
                alice,
                persistentListOf(Target.Permanent(CREATURE_ID)),
                source = AXE_ID,
                sourceCard = AXE,
            ),
        )

/** A board with the Axe and one 2/2 creature Alice controls, both unattached. */
private fun axeState(): GameState =
    GameState(
        players = persistentMapOf(alice to emptySeat(), bob to emptySeat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(AXE_ID, AXE, alice),
                        GameObject(CREATURE_ID, CardRef("Grizzly Bears"), alice),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 2,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )

private fun emptySeat(): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )
