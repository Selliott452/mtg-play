package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Rally at the Hornburg and its token against their oracle text (CR 201–208), and the ordering between
 * its two sentences — which is the only thing about the card that can be silently wrong.
 */
class TokensSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 111.4: the Human Soldier token is a 1/1 with both printed creature types and no mana cost" {
            val printed = humanSoldierToken.characteristics
            printed.manaCost.shouldBeNull()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Soldier"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
            // The token's own text is empty; the haste comes from the spell, until end of turn only.
            printed.keywords shouldBe persistentSetOf<Keyword>()
        }

        "CR 202/205: Rally at the Hornburg is a {1}{R} sorcery with no targets" {
            val printed = rallyAtTheHornburg.characteristics
            printed.manaCost shouldBe ManaCost.parse("{1}{R}")
            printed.cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            printed.powerToughness.shouldBeNull()
            rallyAtTheHornburg.timing shouldBe TimingClass.SORCERY_SPEED
            rallyAtTheHornburg.targetSpec shouldBe TargetSpec.None
        }

        "CR 707.2: Rally at the Hornburg creates two Human Soldier tokens under its controller" {
            val resolved = resolveRally(emptyBoard(alice, bob), alice)
            resolved.sharedZones.battlefield shouldHaveSize RALLY_AT_THE_HORNBURG_TOKENS
            resolved.sharedZones.battlefield.forEach { token ->
                token.card shouldBe CardRef("Human Soldier")
                token.owner shouldBe alice
            }
            resolved.events.filterIsInstance<GameEvent.TokenCreated>() shouldHaveSize
                RALLY_AT_THE_HORNBURG_TOKENS
            // CR 111: the token flows through the engine as an ordinary object whose definition is a
            // TokenDefinition — that is the whole "this object is a token" test.
            (resolved.definitions[CardRef("Human Soldier")] is TokenDefinition) shouldBe true
        }

        "CR 608.2: the tokens are created before the pump, so they are Humans it gives haste to" {
            val resolved = resolveRally(emptyBoard(alice, bob), alice)
            // One until-end-of-turn effect per Human, and the only Humans on this board are the two
            // tokens the same resolution just made. Creating them after the pump would leave zero.
            resolved.timedEffects shouldHaveSize RALLY_AT_THE_HORNBURG_TOKENS
            resolved.timedEffects.forEach { effect ->
                effect.duration shouldBe EffectDuration.UntilEndOfTurn
                effect.modification.grantedKeywords shouldBe persistentSetOf(Keyword.HASTE)
                effect.sourceCard shouldBe CardRef("Rally at the Hornburg")
            }
        }

        "CR 205.3: the pump reaches a Human already on the battlefield, and only its controller's" {
            val yourHuman = GameObject(ObjectId(0), CardRef("God-Pharaoh's Faithful"), alice)
            val theirHuman = GameObject(ObjectId(1), CardRef("God-Pharaoh's Faithful"), bob)
            val yourNonHuman = GameObject(ObjectId(2), CardRef("Grizzly Bears"), alice)
            val board = emptyBoard(alice, bob, yourHuman, theirHuman, yourNonHuman)
            val resolved = resolveRally(board, alice)
            // Two tokens plus the one Human you already controlled; the opponent's Human and your
            // Bear are untouched (CR 109.5 "you", CR 205.3 the creature type).
            resolved.timedEffects shouldHaveSize RALLY_AT_THE_HORNBURG_TOKENS + 1
            resolved.timedEffects.none { it.affected == theirHuman.id } shouldBe true
            resolved.timedEffects.none { it.affected == yourNonHuman.id } shouldBe true
            resolved.timedEffects.any { it.affected == yourHuman.id } shouldBe true
        }
    })

/** Resolves Rally at the Hornburg for [controller] against [state] (CR 608.2). */
private fun resolveRally(
    state: GameState,
    controller: PlayerId,
): GameState = rallyAtTheHornburg.resolution.resolve(state, ResolutionContext(controller, persistentListOf()))

/** A two-seat state whose battlefield holds [permanents] and whose definitions are the MVP registry. */
private fun emptyBoard(
    alice: PlayerId,
    bob: PlayerId,
    vararg permanents: GameObject,
): GameState {
    fun seat() =
        PlayerState(
            life = 20,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(*permanents),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = permanents.size.toLong(),
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
