package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.ReplacementEffect
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The ten Mono-Red Madness definitions (docs/decklists.md): printed characteristics against the oracle
 * card (CR 201–208), the cast-from-elsewhere permissions and cost data the engine reads, and each card's
 * self-contained resolution/trigger effect exercised directly (CR 120, CR 601.3b, CR 707). Full
 * engine-driven headline behaviour (madness off a Grab discard, Fireblast off two sacrifices, Snacker's
 * third-draw return, Guttersnipe's ping) lives in the acceptance module, which drives the cast pipeline.
 * The three architect-gap resolutions (Highway Robbery, Faithless Looting, and — on a token — Blood) are
 * pinned here as the loud, conscious failures the P6.2b report STOP-flags.
 */
class MadnessDeckSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 202: printed characteristics of every Madness card match the oracle" {
            with(guttersnipe.characteristics) {
                name shouldBe "Guttersnipe"
                manaCost?.render() shouldBe "{2}{R}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Goblin"), Subtype("Shaman"))
                powerToughness shouldBe PrintedPowerToughness(2, 2)
            }
            with(sneakySnacker.characteristics) {
                name shouldBe "Sneaky Snacker"
                manaCost?.render() shouldBe "{U}{B}"
                subtypes shouldBe persistentSetOf(Subtype("Faerie"), Subtype("Rogue"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
            }
            with(voldarenEpicure.characteristics) {
                name shouldBe "Voldaren Epicure"
                manaCost?.render() shouldBe "{R}"
                subtypes shouldBe persistentSetOf(Subtype("Vampire"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
            }
            fieryTemper.characteristics.manaCost?.render() shouldBe "{1}{R}{R}"
            fieryTemper.characteristics.cardTypes shouldBe persistentSetOf(CardType.INSTANT)
            fireblast.characteristics.manaCost?.render() shouldBe "{4}{R}{R}"
            lavaDart.characteristics.manaCost?.render() shouldBe "{R}"
            grabThePrize.characteristics.cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            meldedMoxite.characteristics.cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
            meldedMoxite.characteristics.powerToughness.shouldBeNull()
            highwayRobbery.characteristics.manaCost?.render() shouldBe "{1}{R}"
            faithlessLooting.characteristics.manaCost?.render() shouldBe "{R}"
        }

        "CR 601.3a: the Madness creatures and Moxite are sorcery-speed untargeted permanent spells" {
            listOf(guttersnipe, sneakySnacker, voldarenEpicure, meldedMoxite).forEach {
                it.timing shouldBe TimingClass.SORCERY_SPEED
                it.targetSpec shouldBe TargetSpec.None
            }
        }

        "CR 603.2e: Guttersnipe fires on your instant or sorcery and pings each opponent for 2" {
            val trigger = guttersnipe.triggeredAbilities.single()
            trigger.condition shouldBe
                TriggerCondition.SpellCast(
                    spellTypes = persistentSetOf(CardType.INSTANT, CardType.SORCERY),
                    controlledByYou = true,
                )
            val resolved = trigger.effect.resolve(twoPlayerState(alice, bob), noTargets(alice))
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE - GUTTERSNIPE_DAMAGE
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE
        }

        "CR 603.2e: Kessig Flamebreather fires on any noncreature spell you cast and pings each opponent for 1" {
            with(kessigFlamebreather.characteristics) {
                name shouldBe "Kessig Flamebreather"
                manaCost?.render() shouldBe "{1}{R}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Shaman"))
                powerToughness shouldBe PrintedPowerToughness(1, 3)
            }
            val trigger = kessigFlamebreather.triggeredAbilities.single()
            // The oracle text is "a noncreature spell": an exclusion, never an instant-or-sorcery whitelist.
            trigger.condition shouldBe
                TriggerCondition.SpellCast(
                    excludedSpellTypes = persistentSetOf(CardType.CREATURE),
                    controlledByYou = true,
                )
            val resolved = trigger.effect.resolve(twoPlayerState(alice, bob), noTargets(alice))
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE - KESSIG_FLAMEBREATHER_DAMAGE
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE
        }

        "CR 603.2: Sneaky Snacker's third-draw return functions from the graveyard" {
            val trigger = sneakySnacker.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.DrewNthCardThisTurn(SNEAKY_SNACKER_DRAW_ORDINAL)
            trigger.zoneScope shouldBe TriggerZoneScope.Graveyard
        }

        "CR 603.6a: Voldaren Epicure's ETB burns each opponent for 1 and creates a Blood token" {
            val trigger = voldarenEpicure.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            val resolved = trigger.effect.resolve(twoPlayerState(alice, bob), noTargets(alice))
            resolved.players.getValue(bob).life shouldBe STARTING_LIFE - VOLDAREN_EPICURE_DAMAGE
            resolved.sharedZones.battlefield.count { it.card == CardRef("Blood") } shouldBe 1
        }

        "CR 115.4: Fiery Temper, Fireblast, and Lava Dart deal their printed damage to the targeted player" {
            listOf(
                fieryTemper to FIERY_TEMPER_DAMAGE,
                fireblast to FIREBLAST_DAMAGE,
                lavaDart to LAVA_DART_DAMAGE,
            ).forEach { (card, damage) ->
                card.targetSpec shouldBe TargetSpec.AnyTarget
                val resolved =
                    card.resolution.resolve(
                        twoPlayerState(alice, bob),
                        ResolutionContext(alice, persistentListOf(Target.Player(bob))),
                    )
                resolved.players.getValue(bob).life shouldBe STARTING_LIFE - damage
            }
        }

        "CR 702.35: Fiery Temper carries madness — the discard→exile replacement and the {R} reflexive cast" {
            fieryTemper.replacementEffects shouldContainExactly listOf(ReplacementEffect.DiscardToExileInstead)
            val madness = fieryTemper.castingPermissions.single()
            madness shouldBe CastingPermission.Madness(ManaCost.parse("{R}"))
        }

        "CR 118.9: Fireblast's alternative cost sacrifices two Mountains for {0} instead of {4}{R}{R}" {
            val alt = fireblast.castingPermissions.single()
            check(alt is CastingPermission.AlternativeCost)
            alt.cost shouldBe ManaCost.parse("{0}")
            alt.sacrifice?.count shouldBe 2
            alt.sacrifice?.subtype shouldBe Subtype("Mountain")
        }

        "CR 702.34c: Lava Dart's flashback sacrifices a Mountain for {0} and exiles the spell off the stack" {
            val flashback = lavaDart.castingPermissions.single()
            check(flashback is CastingPermission.Flashback)
            flashback.cost shouldBe ManaCost.parse("{0}")
            flashback.sacrifice?.count shouldBe 1
            flashback.sacrifice?.subtype shouldBe Subtype("Mountain")
            flashback.exilesOnLeaveStack shouldBe true
        }

        "CR 601.2b: Grab the Prize costs an extra discard, draws two, and reads the discard's identity" {
            grabThePrize.additionalCost shouldBe AdditionalCost.DiscardCards(1)
            // A discarded non-land (Lightning Bolt): draw two and deal 2 to each opponent.
            val afterNonLand =
                grabThePrize.resolution.resolve(
                    drawableState(alice, bob),
                    noTargets(alice, discarded = listOf(CardRef("Lightning Bolt"))),
                )
            afterNonLand.players.getValue(bob).life shouldBe STARTING_LIFE - GRAB_THE_PRIZE_DAMAGE
            afterNonLand.players.getValue(alice).drawsThisTurn shouldBe GRAB_THE_PRIZE_DRAW
            // A discarded land (Mountain): draw two, but no damage.
            val afterLand =
                grabThePrize.resolution.resolve(
                    drawableState(alice, bob),
                    noTargets(alice, discarded = listOf(CardRef("Mountain"))),
                )
            afterLand.players.getValue(bob).life shouldBe STARTING_LIFE
            afterLand.players.getValue(alice).drawsThisTurn shouldBe GRAB_THE_PRIZE_DRAW
        }

        "CR 603.6a / CR 601.3b: Melded Moxite's ETB is an optional discard-then-draw of two" {
            val trigger = meldedMoxite.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.optionalDiscardDraw?.drawCount shouldBe MELDED_MOXITE_DRAW
        }

        "CR 602 / CR 707.2: Melded Moxite's {3}, sacrifice ability creates a tapped Robot token" {
            val ability = meldedMoxite.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{3}")), AbilityCost.SacrificeSelf)
            val created = ability.effect.resolve(twoPlayerState(alice, bob), noTargets(alice))
            val robot = created.sharedZones.battlefield.single { it.card == CardRef("Robot") }
            robot.tapped shouldBe true
        }

        "CR 702.140 / CR 601.3b: Highway Robbery plots for {1}{R}; resolution is an optional cost-then-draw of two" {
            highwayRobbery.castingPermissions shouldContainExactly
                listOf(CastingPermission.Plot(ManaCost.parse("{1}{R}")))
            // The ordinary resolution is a no-op; the clause carries the two modes and the draw count.
            val base = twoPlayerState(alice, bob)
            highwayRobbery.resolution.resolve(base, noTargets(alice)) shouldBe base
            val clause =
                highwayRobbery.optionalCostThenDraw ?: error("Highway Robbery declares an optional cost-then-draw")
            clause.drawCount shouldBe HIGHWAY_ROBBERY_DRAW
            clause.modes shouldContainExactly listOf(OptionalCostMode.DiscardCard, OptionalCostMode.SacrificeLand)
        }

        "CR 702.34 / CR 601.2c: Faithless Looting flashes back for {2}{R}; its resolution draws two then discards two" {
            val flashback = faithlessLooting.castingPermissions.single()
            flashback shouldBe CastingPermission.Flashback(ManaCost.parse("{2}{R}"))
            // The ordinary resolution is a no-op; the draw-then-discard declaration carries both counts.
            val base = twoPlayerState(alice, bob)
            faithlessLooting.resolution.resolve(base, noTargets(alice)) shouldBe base
            val clause = faithlessLooting.drawThenDiscard ?: error("Faithless Looting declares a draw-then-discard")
            clause.drawCount shouldBe FAITHLESS_LOOTING_DRAW
            clause.discardCount shouldBe FAITHLESS_LOOTING_DISCARD
        }

        "CR 111.4 / CR 602: the Blood token is a colorless artifact with a {1},{T},discard,sacrifice loot" {
            with(bloodToken.characteristics) {
                name shouldBe "Blood"
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf(Subtype("Blood"))
                powerToughness.shouldBeNull()
            }
            // The completed ability (P6.2c): the composite cost in printed order, drawing one on resolution.
            val loot = bloodToken.activatedAbilities.single()
            loot.cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{1}")),
                    AbilityCost.TapSelf,
                    AbilityCost.DiscardACard,
                    AbilityCost.SacrificeSelf,
                )
            val drawn = loot.effect.resolve(drawableState(alice, bob), noTargets(alice))
            drawn.players.getValue(alice).drawsThisTurn shouldBe BLOOD_TOKEN_DRAW
            bloodToken.manaAbilities.shouldBeEmpty()
        }

        "CR 111.4: the Robot token is a 2/2 colorless Robot artifact creature" {
            with(robotToken.characteristics) {
                name shouldBe "Robot"
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Robot"))
                powerToughness shouldBe PrintedPowerToughness(2, 2)
            }
        }
    })

private const val STARTING_LIFE: Int = 20

/** A resolution context for [seat] with no targets and the given [discarded] linked information. */
private fun noTargets(
    seat: PlayerId,
    discarded: List<CardRef> = emptyList(),
): ResolutionContext = ResolutionContext(seat, persistentListOf(), discardedForCost = discarded.toPersistentList())

/** A two-player main-phase state over [MvpCards], both seats at 20 with empty zones — for effect tests. */
private fun twoPlayerState(
    alice: PlayerId,
    bob: PlayerId,
): GameState = drawableState(alice, bob, aliceLibrary = emptyList())

/**
 * A two-player main-phase state over [MvpCards] where alice's library holds [aliceLibrary] (Mountains by
 * default, so a draw effect has cards to take). Both seats at 20 with otherwise empty zones.
 */
private fun drawableState(
    alice: PlayerId,
    bob: PlayerId,
    aliceLibrary: List<String> = listOf("Mountain", "Mountain"),
): GameState {
    val aliceObjects = aliceLibrary.mapIndexed { i, name -> GameObject(ObjectId(i.toLong()), CardRef(name), alice) }

    fun seat(library: List<GameObject> = emptyList()) =
        PlayerState(
            life = STARTING_LIFE,
            library = library.toPersistentList(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(aliceObjects), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
