package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.ClauseCondition
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetCondition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.declaredClauses
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.DeathReplacement
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `W8-C` removal family (BurnAndRemoval.kt) against the oracle cards: printed characteristics
 * (CR 201–208) and the declaration each printed clause maps onto. Every card's *behaviour* is played
 * through the engine elsewhere — the two new trigger conditions in `mtg-rules`' `TapAndDamageTriggerSpec`
 * and the target-conditional cost in its `TargetConditionalCostSpec` — so this suite pins the data, one
 * assertion per printed line.
 */
class BurnAndRemovalSpec :
    StringSpec({

        "CR 202: Dust to Dust is a {1}{W}{W} sorcery" {
            with(dustToDust.characteristics) {
                name shouldBe "Dust to Dust"
                manaCost?.render() shouldBe "{1}{W}{W}"
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.WHITE)
            }
            dustToDust.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 115.1b: 'exile two target artifacts' is the artifact noun with an exact count of two" {
            dustToDust.targetSpec shouldBe
                TargetSpec.TargetPermanent(
                    restriction = PermanentRestriction.ARTIFACT,
                    count = TargetCount.Exactly(2),
                )
            // CR 601.2c: the minimum is what makes it uncastable against a single artifact — a demand,
            // not an "up to". Both bounds are two.
            dustToDust.targetSpec.count.minimum shouldBe 2
            dustToDust.targetSpec.count.maximum shouldBe 2
        }

        "CR 202: Cryoshatter is a {U} Enchantment — Aura" {
            with(cryoshatter.characteristics) {
                name shouldBe "Cryoshatter"
                manaCost?.render() shouldBe "{U}"
                cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
                subtypes shouldBe persistentSetOf(Subtype("Aura"))
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.BLUE)
            }
            // CR 601.3a: an Aura is an enchantment spell, cast at sorcery speed.
            cryoshatter.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 303.4a: 'Enchant creature' is the unrestricted creature enchant restriction" {
            cryoshatter.targetSpec shouldBe TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        }

        "CR 613.3 sublayer 7c: 'enchanted creature gets -5/-0' is a power modifier alone" {
            val effect = cryoshatter.staticContinuousEffects.single()
            effect.powerMod shouldBe Magnitude.Fixed(-5)
            effect.toughnessMod shouldBe Magnitude.Zero
        }

        "CR 603.2: 'becomes tapped or is dealt damage' is one ability with a disjunctive condition" {
            val ability = cryoshatter.triggeredAbilities.single()
            ability.condition shouldBe
                TriggerCondition.AnyOf(
                    persistentListOf(
                        TriggerCondition.EnchantedPermanentBecomesTapped,
                        TriggerCondition.EnchantedPermanentIsDealtDamage,
                    ),
                )
        }

        "CR 202: Ride's End is a {4}{W} instant" {
            with(ridesEnd.characteristics) {
                name shouldBe "Ride's End"
                manaCost?.render() shouldBe "{4}{W}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.WHITE)
            }
            ridesEnd.timing shouldBe TimingClass.INSTANT_SPEED
        }

        "CR 115.1b: 'exile target creature or Vehicle' is the creature-or-Vehicle noun, one target" {
            ridesEnd.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_OR_VEHICLE)
            ridesEnd.targetSpec.count shouldBe TargetCount.ONE
        }

        "CR 601.2f: 'costs {3} less if it targets a tapped permanent' is a target-conditional reduction" {
            ridesEnd.costReduction shouldBe
                CostReduction.IfTargets(amount = 3, condition = TargetCondition.TAPPED_PERMANENT)
        }

        "the three cards carry no cost or casting machinery their oracle text does not print" {
            listOf(dustToDust, cryoshatter, ridesEnd).forEach { card ->
                card.castingPermissions shouldContainExactly emptyList()
                card.additionalCost.shouldBeNull()
                card.kicker.shouldBeNull()
                card.counterUnlessPaid.shouldBeNull()
                card.rebound shouldBe false
                card.modes shouldContainExactly emptyList()
            }
            // Only Ride's End prices itself off anything.
            dustToDust.costReduction.shouldBeNull()
            cryoshatter.costReduction.shouldBeNull()
        }

        "the registry holds all four under their printed names (CR 201)" {
            listOf("Dust to Dust", "Cryoshatter", "Ride's End", "Torch the Tower").forEach { name ->
                MvpCards.definitions
                    .getValue(CardRef(name))
                    .characteristics.name shouldBe name
            }
        }

        // ---- Torch the Tower (`W9-D`) ---------------------------------------------------------------

        "CR 202: Torch the Tower is a {R} instant" {
            with(torchTheTower.characteristics) {
                name shouldBe "Torch the Tower"
                manaCost?.render() shouldBe "{R}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.RED)
            }
            torchTheTower.timing shouldBe TimingClass.INSTANT_SPEED
        }

        "CR 702.166a: the printed keyword is bargain, the optional additional cost with a chosen object" {
            torchTheTower.optionalAdditionalCost shouldBe OptionalAdditionalCost.Bargain
            // It is emphatically not the mandatory sacrifice cost and not a kicker: an unbargained Torch
            // the Tower is a perfectly ordinary one-mana instant.
            torchTheTower.additionalCost.shouldBeNull()
            torchTheTower.kicker.shouldBeNull()
        }

        "CR 115.1: 'target creature or planeswalker' is target creature in a commons format" {
            // Pauper prints no CR 306 planeswalker, so the second noun names nothing any gauntlet board
            // can hold — the same narrowing BurnAndRemoval.kt records for Searing Blaze.
            torchTheTower.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 702.166b: the scry is declared as a clause gated on the bargain, not as an unconditional one" {
            torchTheTower.libraryLook shouldBe LibraryLook(LibraryLookMode.Scry(1))
            torchTheTower.clauseCondition shouldBe ClauseCondition.SpellPaidOptionalAdditionalCost
            // One clause, so the orchestration is a dispatch rather than an ordering (CR 608.2c).
            torchTheTower.declaredClauses.size shouldBe 1
        }

        "CR 702.166b: an unbargained Torch the Tower deals 2 and watches the permanent it damaged" {
            val state = torchState()
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val resolved = resolveTorch(state, bear, bargained = false)

            resolved.sharedZones.battlefield
                .single()
                .damageMarked shouldBe 2
            // CR 614.1a: the rider is unconditional — it is printed on its own line, not on the
            // bargained branch, so it applies to both.
            resolved.deathReplacements.single().affected shouldBe setOf(bear)
        }

        "CR 702.166b: a bargained Torch the Tower deals 3 instead — one damage event, not two" {
            val state = torchState()
            val bear =
                state.sharedZones.battlefield
                    .single()
                    .id
            val resolved = resolveTorch(state, bear, bargained = true)

            resolved.sharedZones.battlefield
                .single()
                .damageMarked shouldBe 3
            // "Instead" replaces the printed amount, so exactly one CR 120 event happened.
            resolved.events.filterIsInstance<GameEvent.DamageDealt>().map { it.amount } shouldContainExactly listOf(3)
        }

        "CR 614.1a: the rider names the spell as the replacement's source, whichever branch was taken" {
            listOf(true, false).forEach { bargained ->
                val state = torchState()
                val bear =
                    state.sharedZones.battlefield
                        .single()
                        .id
                val stored = resolveTorch(state, bear, bargained).deathReplacements.single()
                stored.sourceCard shouldBe CardRef("Torch the Tower")
                stored.effect shouldBe DeathReplacement.ExileInstead
            }
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)
private const val TORCH_STARTING_LIFE: Int = 20

/** Torch the Tower's resolution against [target], with the bargain answered [bargained] (CR 601.2b). */
private fun resolveTorch(
    state: GameState,
    target: ObjectId,
    bargained: Boolean,
): GameState =
    torchTheTower.resolution.resolve(
        state,
        ResolutionContext(
            controller = alice,
            targets = persistentListOf(Target.Permanent(target)),
            sourceCard = CardRef("Torch the Tower"),
            optionalCostPaid = bargained,
        ),
    )

/** A two-player state with one opposing creature from the real registry. */
private fun torchState(): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = TORCH_STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
                bob to
                    PlayerState(
                        life = TORCH_STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(GameObject(ObjectId(0), CardRef("Sea Gate Oracle"), bob)),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
