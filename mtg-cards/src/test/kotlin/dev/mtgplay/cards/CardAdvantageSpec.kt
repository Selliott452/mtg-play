package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ChosenTypeReveal
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.declaredClauses
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The printed half of the card-advantage cards, read line by line off the Scryfall oracle text
 * (CR 201–205): Mulldrifter's evoke and its two enters triggers, Winding Way's resolution-time type
 * choice, and Reckless Impulse's play-from-exile grant.
 *
 * Engine-driven behaviour — that an evoked Mulldrifter is sacrificed and a hard-cast one is not, that
 * Winding Way's four revealed cards partition by the chosen type, and that Reckless Impulse's permission
 * survives exactly to the end of its controller's next turn — lives in the rules module, because none of
 * it is observable from a definition alone.
 */
class CardAdvantageSpec :
    StringSpec({

        "CR 302.1: Mulldrifter is a {4}{U} 2/2 Elemental with flying, cast at sorcery speed" {
            with(mulldrifter.characteristics) {
                name shouldBe "Mulldrifter"
                manaCost shouldBe ManaCost.parse("{4}{U}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Elemental"))
                powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 2)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
            }
            mulldrifter.timing shouldBe TimingClass.SORCERY_SPEED
            mulldrifter.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.74a: Mulldrifter's evoke is an alternative cost of {2}{U} cast from the hand" {
            val evoke = mulldrifter.castingPermissions.single()
            evoke shouldBe CastingPermission.Evoke(ManaCost.parse("{2}{U}"))
            // CR 118.9: it *replaces* the printed cost rather than adding to it, and it is a hand cast —
            // the same card is still castable normally, which is a second, distinct enumerated option.
            evoke.source shouldBe CastSource.HAND
            evoke.cost shouldBe ManaCost.parse("{2}{U}")
            // CR 702.34e is flashback's, not evoke's: an evoked spell resolves into a permanent normally
            // and reaches the graveyard by the sacrifice, not off the stack.
            evoke.exilesOnLeaveStack shouldBe false
            evoke.offeredAtPriority shouldBe true
        }

        "CR 702.74a: Mulldrifter has two enters triggers, and only the sacrifice has an intervening if" {
            val (draw, sacrifice) = mulldrifter.triggeredAbilities
            mulldrifter.triggeredAbilities.size shouldBe 2

            // "When this creature enters, draw two cards" — unconditional, so it fires on every entry.
            draw.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            draw.interveningIf.shouldBeNull()
            draw.targetSpec shouldBe TargetSpec.None

            // "When this permanent enters, if its evoke cost was paid, sacrifice it" — CR 603.4 gates the
            // *firing*, so a hard-cast Mulldrifter never puts this ability on the stack at all.
            sacrifice.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            sacrifice.interveningIf shouldBe InterveningIf.SourceWasEvoked
        }

        "CR 120.1: Mulldrifter's enters trigger draws exactly two" {
            MULLDRIFTER_DRAW shouldBe 2
        }

        "CR 307.1: Winding Way is a {1}{G} sorcery that targets nothing" {
            with(windingWay.characteristics) {
                name shouldBe "Winding Way"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }
            windingWay.timing shouldBe TimingClass.SORCERY_SPEED
            windingWay.targetSpec shouldBe TargetSpec.None
            windingWay.castingPermissions.shouldBeEmpty()
        }

        "CR 609.4: Winding Way chooses creature or land as it resolves, then reveals four" {
            windingWay.chosenTypeReveal shouldBe
                ChosenTypeReveal(
                    count = WINDING_WAY_REVEAL,
                    choices = persistentListOf(RevealedCardFilter.CREATURE_CARD, RevealedCardFilter.LAND_CARD),
                )
            WINDING_WAY_REVEAL shouldBe 4
            // CR 601.2b: it is *not* a modal card. A mode would be chosen while casting, a whole priority
            // round before the choice the card actually prints.
            windingWay.modes.shouldBeEmpty()
            // CR 701.16: it is not the "up to M" reveal either — that clause would offer keeping fewer.
            windingWay.libraryReveal.shouldBeNull()
            windingWay.libraryLook.shouldBeNull()
        }

        "CR 307.1: Reckless Impulse is a {1}{R} sorcery whose whole effect is the exile grant" {
            with(recklessImpulse.characteristics) {
                name shouldBe "Reckless Impulse"
                manaCost shouldBe ManaCost.parse("{1}{R}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }
            recklessImpulse.timing shouldBe TimingClass.SORCERY_SPEED
            recklessImpulse.targetSpec shouldBe TargetSpec.None
            RECKLESS_IMPULSE_EXILE shouldBe 2
        }

        "CR 118.5: Reckless Impulse declares no casting permission — the grant is not one" {
            // The permission it hands out belongs to the *exiled cards*, not to Reckless Impulse, and is
            // granted by its resolution rather than declared on any card. A CastingPermission here would
            // be a permission to cast Reckless Impulse itself, which is a different sentence entirely.
            recklessImpulse.castingPermissions.shouldBeEmpty()
            recklessImpulse.chosenTypeReveal.shouldBeNull()
            recklessImpulse.libraryReveal.shouldBeNull()
            recklessImpulse.triggeredAbilities.shouldBeEmpty()
        }

        // ---- Monstrous Emergence (`W9-D`) ------------------------------------------------------------

        "CR 202: Monstrous Emergence is a {1}{G} sorcery targeting a creature" {
            with(monstrousEmergence.characteristics) {
                name shouldBe "Monstrous Emergence"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            monstrousEmergence.timing shouldBe TimingClass.SORCERY_SPEED
            monstrousEmergence.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 601.2b: the additional cost is the non-consuming choose-or-reveal, not a sacrifice" {
            monstrousEmergence.additionalCost shouldBe AdditionalCost.ChooseCreatureOrRevealCreatureCard
            // Nothing else on the card is a cost: no kicker, no bargain, no casting permission.
            monstrousEmergence.kicker.shouldBeNull()
            monstrousEmergence.optionalAdditionalCost.shouldBeNull()
            monstrousEmergence.castingPermissions.shouldBeEmpty()
            monstrousEmergence.declaredClauses.shouldBeEmpty()
        }

        "CR 613: a chosen creature's damage is its layered power, read as the spell resolves" {
            val state = emergenceState()
            val ogre = state.sharedZones.battlefield.single { it.card == CardRef("Grizzly Bears") }
            val target = state.sharedZones.battlefield.single { it.card == CardRef("Sea Gate Oracle") }
            val resolved = resolveEmergence(state, target.id, ChosenPowerSource.ChosenCreature(ogre.id))

            resolved.sharedZones.battlefield.single { it.id == target.id }.damageMarked shouldBe GRIZZLY_POWER
        }

        "CR 109.3: a revealed card's damage is its printed power — the layer system does not reach a hand" {
            val state = emergenceState()
            val target = state.sharedZones.battlefield.single { it.card == CardRef("Sea Gate Oracle") }
            val resolved =
                resolveEmergence(state, target.id, ChosenPowerSource.RevealedCard(CardRef("Grizzly Bears")))

            resolved.sharedZones.battlefield.single { it.id == target.id }.damageMarked shouldBe GRIZZLY_POWER
        }
    })

private val emergenceAlice = PlayerId(0)
private val emergenceBob = PlayerId(1)
private const val EMERGENCE_LIFE: Int = 20

/** Grizzly Bears is the pool's plain 2/2 — its printed power, and its layered one on an empty board. */
private const val GRIZZLY_POWER: Int = 2

/** Monstrous Emergence's resolution against [target], with [named] as the cost's linked information. */
private fun resolveEmergence(
    state: GameState,
    target: ObjectId,
    named: ChosenPowerSource,
): GameState =
    monstrousEmergence.resolution.resolve(
        state,
        ResolutionContext(
            controller = emergenceAlice,
            targets = persistentListOf(Target.Permanent(target)),
            sourceCard = CardRef("Monstrous Emergence"),
            costPowerSource = named,
        ),
    )

/** A two-player board with alice's Grizzly Bears and bob's Sea Gate Oracle, over the real registry. */
private fun emergenceState(): GameState {
    fun seat() =
        PlayerState(
            life = EMERGENCE_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(emergenceAlice to seat(), emergenceBob to seat()),
        turn = Turn(emergenceAlice, 5, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(ObjectId(0), CardRef("Grizzly Bears"), emergenceAlice),
                        GameObject(ObjectId(1), CardRef("Sea Gate Oracle"), emergenceBob),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
