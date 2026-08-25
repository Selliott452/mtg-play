package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Timberwatch Elf (TimedEffectCards.kt) against the oracle card: the printed line, the shape of its
 * activated ability, and the three details of "+X/+X until end of turn, where X is the number of
 * Elves on the battlefield" that are silently wrong if got wrong — the count's *scope*, whether it
 * counts itself, and the CR 608.2h read point that freezes it.
 *
 * The activation path itself — priority, the CR 302.6 summoning-sickness gate, target choice, the
 * CR 608.2b re-check, and the CR 514.2 wear-off — is played end-to-end in
 * `DurationAcceptanceSpec`; this suite pins the card.
 */
class TimedEffectCardsSpec :
    StringSpec({

        "CR 202/205/208: Timberwatch Elf's printed line matches the oracle card" {
            with(timberwatchElf.characteristics) {
                name shouldBe "Timberwatch Elf"
                manaCost?.render() shouldBe "{2}{G}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Elf"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 2)
                keywords.shouldBeEmpty()
            }
            // CR 302.1: a creature spell is cast at sorcery speed and targets nothing.
            timberwatchElf.timing shouldBe TimingClass.SORCERY_SPEED
            timberwatchElf.targetSpec shouldBe TargetSpec.None
        }

        "CR 602.1/115.1b: the ability costs a bare {T} and targets one creature, either player's" {
            val ability = timberwatchElf.activatedAbilities.single()
            // A bare {T} — no mana component — is why trap T17 (a mana source funding its own {T}
            // ability) is unreachable from this card, unlike Basilisk Gate's "{2}, {T}".
            ability.cost shouldBe persistentListOf(AbilityCost.TapSelf)
            // "Target creature" carries no control clause (CR 115.1b).
            ability.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 205.3: X counts every Elf on the battlefield, not only the ones you control" {
            // Alice's Timberwatch Elf and Elvish Mystic, plus *Bob's* Fyndhorn Elves: X = 3, not 2.
            val state =
                boardState(
                    listOf(
                        bfObject(0, "Timberwatch Elf", alice),
                        bfObject(1, "Elvish Mystic", alice),
                        bfObject(2, "Fyndhorn Elves", bob),
                        bfObject(3, "Grizzly Bears", alice),
                    ),
                )
            val pumped = activate(state, source = 0, target = 3)
            layeredPower(pumped, ObjectId(3)) shouldBe 2 + 3
            layeredToughness(pumped, ObjectId(3)) shouldBe 2 + 3
        }

        "CR 205.3: the Elf counts itself, so a lone Timberwatch Elf gives exactly +1/+1" {
            val state =
                boardState(listOf(bfObject(0, "Timberwatch Elf", alice), bfObject(1, "Grizzly Bears", alice)))
            val pumped = activate(state, source = 0, target = 1)
            layeredPower(pumped, ObjectId(1)) shouldBe 3
            layeredToughness(pumped, ObjectId(1)) shouldBe 3
        }

        "CR 608.2h/611.2d: X is calculated once on resolution and does not grow when a later Elf enters" {
            // The T16 trap. With two Elves out the pump is +2/+2; a third Elf arriving afterwards must
            // not make it +3/+3, which is exactly what reusing Magnitude.Dynamic would have done.
            val state =
                boardState(
                    listOf(
                        bfObject(0, "Timberwatch Elf", alice),
                        bfObject(1, "Elvish Mystic", alice),
                        bfObject(2, "Grizzly Bears", alice),
                    ),
                )
            val pumped = activate(state, source = 0, target = 2)
            layeredPower(pumped, ObjectId(2)) shouldBe 4

            val arrivingElfId = pumped.nextObjectId
            val moreElves =
                pumped.copy(
                    sharedZones =
                        pumped.sharedZones.copy(
                            battlefield =
                                pumped.sharedZones.battlefield
                                    .adding(bfObject(arrivingElfId, "Fyndhorn Elves", alice)),
                        ),
                    nextObjectId = arrivingElfId + 1,
                )
            layeredPower(moreElves, ObjectId(2)) shouldBe 4
            layeredToughness(moreElves, ObjectId(2)) shouldBe 4
        }

        "CR 611.2: the ability stores one until-end-of-turn effect naming its target and its source" {
            val state =
                boardState(listOf(bfObject(0, "Timberwatch Elf", alice), bfObject(1, "Grizzly Bears", alice)))
            val effect = activate(state, source = 0, target = 1).timedEffects.single()
            effect.affected shouldBe ObjectId(1)
            effect.duration shouldBe EffectDuration.UntilEndOfTurn
            effect.sourceCard shouldBe CardRef("Timberwatch Elf")
            effect.source shouldBe ObjectId(0)
            effect.modification.powerMod shouldBe 1
            effect.modification.toughnessMod shouldBe 1
            effect.modification.grantedKeywords.shouldBeEmpty()
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)

/** Resolves Timberwatch Elf's ability from battlefield object [source] onto the creature [target]. */
private fun activate(
    state: GameState,
    source: Long,
    target: Long,
): GameState =
    timberwatchElf.activatedAbilities.single().effect.resolve(
        state,
        ResolutionContext(
            controller = alice,
            targets = persistentListOf(Target.Permanent(ObjectId(target))),
            source = ObjectId(source),
        ),
    )

/** A battlefield [GameObject] over [MvpCards]: [name] resolves via the registry. */
private fun bfObject(
    id: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(id = ObjectId(id), card = CardRef(name), owner = owner)

/** A handcrafted main-phase two-player [GameState] over [MvpCards] with [battlefield] in place. */
private fun boardState(battlefield: List<GameObject>): GameState {
    fun seat() = PlayerState(20, persistentListOf(), persistentListOf(), persistentListOf())
    val nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
