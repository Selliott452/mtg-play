package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.loseLife
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The damage effect primitive (CR 120): damage to a player resolves to life loss (CR 120.3a),
 * narrated as [GameEvent.DamageDealt] then [GameEvent.LifeChanged] — observably distinct from
 * the pure life-loss primitive (CR 119.3c), which later phases' cards depend on.
 */
class DealDamageSpec :
    StringSpec({
        fun startState() =
            twoPlayerState(
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                bobState = playerWithZones(library = mountains(10L..12L, bob)),
                nextObjectId = 100,
            )

        "CR 120.3a: damage dealt to a player causes that player to lose that much life" {
            val damaged = dealDamage(startState(), Target.Player(bob), 3)
            damaged.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            damaged.events shouldBe
                listOf(
                    GameEvent.DamageDealt(Target.Player(bob), 3),
                    GameEvent.LifeChanged(bob, -3, STARTING_LIFE - 3),
                )
        }

        "CR 120.3a vs CR 119.3c: pure life loss is not damage — no DamageDealt event" {
            val lost = loseLife(startState(), bob, 3)
            lost.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            lost.events shouldBe listOf(GameEvent.LifeChanged(bob, -3, STARTING_LIFE - 3))
            lost.events.filterIsInstance<GameEvent.DamageDealt>().shouldBeEmpty()
        }

        "CR 120.8: zero damage is not dealt at all — no state change, no event" {
            val start = startState()
            val untouched = dealDamage(start, Target.Player(bob), 0)
            untouched shouldBeSameInstanceAs start
            untouched.events.shouldBeEmpty()
        }

        "CR 120: a negative damage amount is an error, never silently clamped" {
            shouldThrow<IllegalArgumentException> { dealDamage(startState(), Target.Player(bob), -1) }
        }

        "CR 704.5a: damage may take a life total below zero; the state-based action acts later, not here" {
            val overkill = dealDamage(startState(), Target.Player(bob), STARTING_LIFE + 5)
            overkill.players.getValue(bob).life shouldBe -5
        }
    })
