package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.LibrarySearchAxisCombination
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The `W8-E` creatures against their oracle text (CR 201–208), clause by printed clause.
 *
 * Each assertion names the printed line it is holding the definition to, because every one of these
 * cards has a half that reads like something the engine already had and is not: an intervening-if that
 * is not a resolution check, a colour filter that is not a card-type filter, a "you may search" that is
 * not CR 701.18b's fail-to-find, a disjunctive search filter that is not a narrowing, a graveyard
 * ability that is not a hand ability, and a block restriction that is not a per-pairing one.
 */
class EtbCreaturesSpec :
    StringSpec({
        val alice = PlayerId(0)

        "CR 202/205/208: Faerie Miscreant is a {U} 1/1 Faerie Rogue with flying" {
            val printed = faerieMiscreant.characteristics
            printed.manaCost shouldBe ManaCost.parse("{U}")
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Faerie"), Subtype("Rogue"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
            printed.keywords shouldBe persistentSetOf(Keyword.FLYING)
        }

        "CR 603.4: Faerie Miscreant's draw hangs off an intervening if, not an if inside the effect" {
            val trigger = faerieMiscreant.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // The whole point: declaring it means the ability does not trigger at all when it is false,
            // so no trigger is ordered and no priority round opens for it (CR 603.4, ADR-005).
            trigger.interveningIf shouldBe InterveningIf.YouControlAnotherCreatureNamed("Faerie Miscreant")
        }

        "CR 120.1: Faerie Miscreant's trigger, once it holds, draws exactly one card" {
            val state = singleSeatBoard(alice, libraryCards = 3)
            val resolved =
                faerieMiscreant.triggeredAbilities
                    .single()
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf()))
            resolved.players
                .getValue(alice)
                .hand.size shouldBe FAERIE_MISCREANT_DRAW
            resolved.players
                .getValue(alice)
                .library.size shouldBe 3 - FAERIE_MISCREANT_DRAW
        }

        "CR 202/205/208: God-Pharaoh's Faithful is a {W} 0/4 Human Wizard" {
            val printed = godPharaohsFaithful.characteristics
            printed.manaCost shouldBe ManaCost.parse("{W}")
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Wizard"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 4)
        }

        "CR 105.2: God-Pharaoh's Faithful watches a cast spell's colours, not its card types" {
            val condition =
                godPharaohsFaithful.triggeredAbilities
                    .single()
                    .condition as TriggerCondition.SpellCast
            condition.spellColors shouldBe persistentSetOf(Color.BLUE, Color.BLACK, Color.RED)
            // "you cast" (CR 603.2e) — an opponent's blue spell gains its controller nothing.
            condition.controlledByYou shouldBe true
            // Every card type qualifies: the printed line says "spell", not "instant or sorcery".
            condition.spellTypes shouldBe persistentSetOf<CardType>()
            condition.excludedSpellTypes shouldBe persistentSetOf<CardType>()
        }

        "CR 119.3: God-Pharaoh's Faithful gains exactly one life per qualifying cast" {
            val state = singleSeatBoard(alice, libraryCards = 0)
            val resolved =
                godPharaohsFaithful.triggeredAbilities
                    .single()
                    .effect
                    .resolve(state, ResolutionContext(alice, persistentListOf()))
            resolved.players.getValue(alice).life shouldBe STARTING_LIFE + GOD_PHARAOHS_FAITHFUL_LIFEGAIN
        }

        "CR 202/205/208: Gatecreeper Vine is a {1}{G} 0/2 Plant with defender" {
            val printed = gatecreeperVine.characteristics
            printed.manaCost shouldBe ManaCost.parse("{1}{G}")
            printed.subtypes shouldBe persistentSetOf(Subtype("Plant"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 2)
            printed.keywords shouldBe persistentSetOf(Keyword.DEFENDER)
        }

        "CR 701.18: Gatecreeper Vine searches for a basic land card OR a Gate card, not both at once" {
            val search =
                gatecreeperVine.triggeredAbilities
                    .single()
                    .librarySearch
                    .shouldNotBeNull()
            search.find.basic shouldBe true
            search.find.landTypes shouldBe persistentSetOf(Subtype("Gate"))
            // The axis that keeps a plain Forest and a nonbasic Gate both findable: encoding this as
            // ALL would mean "a basic Gate", which no card in the format is.
            search.find.combination shouldBe LibrarySearchAxisCombination.ANY
            search.destination shouldBe LibrarySearchDestination.REVEALED_TO_HAND
        }

        "CR 601.3b: Gatecreeper Vine's search is optional, which is not CR 701.18b's fail-to-find" {
            gatecreeperVine.triggeredAbilities
                .single()
                .librarySearch
                .shouldNotBeNull()
                .optional shouldBe true
        }

        "CR 701.18: a disjunctive filter needs both axes to say something" {
            runCatching {
                LibrarySearchFilter(basic = true, combination = LibrarySearchAxisCombination.ANY)
            }.isFailure shouldBe true
        }

        "CR 202/205/208: Bramble Wurm is a {6}{G} 7/6 Wurm with reach and trample" {
            val printed = brambleWurm.characteristics
            printed.manaCost shouldBe ManaCost.parse("{6}{G}")
            printed.subtypes shouldBe persistentSetOf(Subtype("Wurm"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 7, toughness = 6)
            printed.keywords shouldBe persistentSetOf(Keyword.REACH, Keyword.TRAMPLE)
            // No evasion: the 7/6 is blocked by any one creature, unlike Troll of Khazad-dûm.
            printed.evasions shouldBe persistentSetOf<Evasion>()
        }

        "CR 113.6b: Bramble Wurm's second lifegain functions from the graveyard and exiles itself to pay" {
            val ability = brambleWurm.activatedAbilities.single()
            ability.zoneScope shouldBe AbilityZoneScope.Graveyard
            ability.cost shouldBe
                persistentListOf(
                    AbilityCost.Mana(ManaCost.parse("{2}{G}")),
                    AbilityCost.ExileSelfFromGraveyard,
                )
            // Paying the cost removes the source, so the ability is once-only without a CR 602.5b
            // restriction saying so.
            ability.oncePerTurn shouldBe false
        }

        "CR 119.3: both of Bramble Wurm's halves gain the same five life" {
            val state = singleSeatBoard(alice, libraryCards = 0)
            val context = ResolutionContext(alice, persistentListOf())
            brambleWurm.triggeredAbilities
                .single()
                .effect
                .resolve(state, context)
                .players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + BRAMBLE_WURM_LIFEGAIN
            brambleWurm.activatedAbilities
                .single()
                .effect
                .resolve(state, context)
                .players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + BRAMBLE_WURM_LIFEGAIN
        }

        "CR 202/205/208: Troll of Khazad-dûm is a {5}{B} 6/5 Troll" {
            val printed = trollOfKhazadDum.characteristics
            printed.manaCost shouldBe ManaCost.parse("{5}{B}")
            printed.subtypes shouldBe persistentSetOf(Subtype("Troll"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 6, toughness = 5)
            // No keyword: "can't be blocked except by three or more creatures" is ability text, and
            // the CR 702.110 keyword that says it with "two" is a different card's.
            printed.keywords shouldBe persistentSetOf<Keyword>()
        }

        "CR 509.1b: Troll of Khazad-dûm's restriction is on the blocker count, not on any blocker" {
            trollOfKhazadDum.characteristics.evasions shouldBe
                persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE)
        }

        "CR 702.28a: swampcycling {1} is a hand ability that discards itself to find a Swamp card" {
            val cycling = trollOfKhazadDum.activatedAbilities.single()
            cycling.zoneScope shouldBe AbilityZoneScope.Hand
            cycling.cost shouldBe
                persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf)
            val search = cycling.librarySearch.shouldNotBeNull()
            search.find shouldBe LibrarySearchFilter.SWAMP_CARD
            // CR 702.28b names a land subtype, never the basic land, so a nonbasic Swamp is findable.
            search.find.basic shouldBe false
            // Typecycling is not optional: "Discard this card:" has already been paid.
            search.optional shouldBe false
            cycling.targetSpec shouldBe TargetSpec.None
        }

        "CR 608.3: every body in this file resolves onto the battlefield with no instructions of its own" {
            val state = singleSeatBoard(alice, libraryCards = 2)
            val context = ResolutionContext(alice, persistentListOf())
            listOf(faerieMiscreant, godPharaohsFaithful, gatecreeperVine, brambleWurm, trollOfKhazadDum)
                .forEach { it.resolution.resolve(state, context) shouldBe state }
        }
    })

/** The life every seat starts a game on (CR 103.3); the baseline the lifegain assertions add to. */
private const val STARTING_LIFE: Int = 20

/** A one-seat state whose library holds [libraryCards] inert cards, for the resolution assertions. */
private fun singleSeatBoard(
    seat: PlayerId,
    libraryCards: Int,
): GameState =
    GameState(
        players =
            persistentMapOf(
                seat to
                    PlayerState(
                        life = STARTING_LIFE,
                        library =
                            (0 until libraryCards)
                                .map { GameObject(ObjectId(it.toLong()), CardRef("Island"), seat) }
                                .toPersistentList(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(seat, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = libraryCards.toLong(),
        rng = Rng(0),
        events = persistentListOf(),
    )
