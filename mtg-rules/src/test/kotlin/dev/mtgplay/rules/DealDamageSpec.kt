package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.DamageSource
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
 *
 * `FW-PREVENT` added the source (CR 120.1) and the CR 615 prevention step; both are pinned here.
 */
class DealDamageSpec :
    StringSpec({
        // A colourless, object-less source — the shape a fixture uses when the test is about the
        // damage and not about who dealt it (CR 105.4: no mana cost, no colour, so no CR 702.16
        // quality matches it).
        val anySource = DamageSource(objectId = null, card = CardRef("Bear"))

        fun startState() =
            twoPlayerState(
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                bobState = playerWithZones(library = mountains(10L..12L, bob)),
                nextObjectId = 100,
            )

        "CR 120.3a: damage dealt to a player causes that player to lose that much life" {
            val damaged = dealDamage(startState(), anySource, Target.Player(bob), 3)
            damaged.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            damaged.events shouldBe
                listOf(
                    GameEvent.DamageDealt(anySource, Target.Player(bob), 3),
                    GameEvent.LifeChanged(bob, -3, STARTING_LIFE - 3),
                )
        }

        "CR 120.1: the DamageDealt event names the object that dealt the damage, not only the recipient" {
            val bolt = DamageSource(objectId = ObjectId(42), card = CardRef("Ogre"))
            val damaged = dealDamage(startState(), bolt, Target.Player(bob), 3)
            damaged.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .single()
                .source shouldBe bolt
        }

        "CR 120.3a vs CR 119.3c: pure life loss is not damage — no DamageDealt event" {
            val lost = loseLife(startState(), bob, 3)
            lost.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            lost.events shouldBe listOf(GameEvent.LifeChanged(bob, -3, STARTING_LIFE - 3))
            lost.events.filterIsInstance<GameEvent.DamageDealt>().shouldBeEmpty()
        }

        "CR 120.8: zero damage is not dealt at all — no state change, no event" {
            val start = startState()
            val untouched = dealDamage(start, anySource, Target.Player(bob), 0)
            untouched shouldBeSameInstanceAs start
            untouched.events.shouldBeEmpty()
        }

        "CR 120: a negative damage amount is an error, never silently clamped" {
            shouldThrow<IllegalArgumentException> { dealDamage(startState(), anySource, Target.Player(bob), -1) }
        }

        "CR 704.5a: damage may take a life total below zero; the state-based action acts later, not here" {
            val overkill = dealDamage(startState(), anySource, Target.Player(bob), STARTING_LIFE + 5)
            overkill.players.getValue(bob).life shouldBe -5
        }

        // The Target.Permanent branch (P3.1): damage to a battlefield object is marked (CR 120.3d),
        // never lost as life — no LifeChanged follows, and nothing dies (the SBA is P3.2).
        fun withBear() =
            handcraftedCombat(
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                aliceField = listOf(Combatant("Bear")),
            )

        "CR 120.3d: damage dealt to a permanent is marked on it, with a DamageDealt and no LifeChanged" {
            val state = withBear()
            val bear = state.creature("Bear").id
            val damaged = dealDamage(state, anySource, Target.Permanent(bear), 2)
            damaged.creature("Bear").damageMarked shouldBe 2
            damaged.events shouldBe listOf(GameEvent.DamageDealt(anySource, Target.Permanent(bear), 2))
            damaged.events.filterIsInstance<GameEvent.LifeChanged>().shouldBeEmpty()
        }

        "CR 120.3d: marked damage from successive sources accumulates on the permanent" {
            val state = withBear()
            val bear = state.creature("Bear").id
            val twice =
                dealDamage(
                    dealDamage(state, anySource, Target.Permanent(bear), 2),
                    anySource,
                    Target.Permanent(bear),
                    1,
                )
            twice.creature("Bear").damageMarked shouldBe 3
        }

        "CR 120.8: zero damage to a permanent is not dealt at all — no mark, no event" {
            val state = withBear()
            val untouched = dealDamage(state, anySource, Target.Permanent(state.creature("Bear").id), 0)
            untouched shouldBeSameInstanceAs state
        }

        "CR 120.3d: marking damage on a non-battlefield object fails loudly, never guesses" {
            shouldThrow<IllegalArgumentException> {
                dealDamage(withBear(), anySource, Target.Permanent(ObjectId(9999)), 1)
            }
        }

        // ---- CR 615 prevention, through CR 702.16e protection (`FW-PREVENT`) ----

        // Warder prints protection from red; Redcap is mono-red, Whitecap mono-white.
        fun withWarder() =
            handcraftedCombat(
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                aliceField = listOf(Combatant("Warder")),
                bobField = listOf(Combatant("Redcap"), Combatant("Whitecap")),
            )

        "CR 702.16e + CR 615.6: damage from a source with the stated quality is prevented — it never happens" {
            val state = withWarder()
            val warder = state.creature("Warder").id
            val red = state.creature("Redcap")
            val source = DamageSource(red.id, red.card)

            val after = dealDamage(state, source, Target.Permanent(warder), 3)

            // CR 615.6: no damage marked, and no DamageDealt at all.
            after.creature("Warder").damageMarked shouldBe 0
            after.events.filterIsInstance<GameEvent.DamageDealt>().shouldBeEmpty()
            after.events shouldBe
                listOf(GameEvent.DamagePrevented(source, Target.Permanent(warder), 3))
        }

        "CR 702.16e: a source without the stated quality is unaffected — white damage hits a red-protected body" {
            val state = withWarder()
            val warder = state.creature("Warder").id
            val white = state.creature("Whitecap")
            val source = DamageSource(white.id, white.card)

            val after = dealDamage(state, source, Target.Permanent(warder), 3)

            after.creature("Warder").damageMarked shouldBe 3
            after.events.filterIsInstance<GameEvent.DamagePrevented>().shouldBeEmpty()
        }

        "CR 120.8 vs CR 615.6: fully prevented damage is not the same as zero damage — the events tell them apart" {
            val state = withWarder()
            val warder = state.creature("Warder").id
            val red = state.creature("Redcap")
            val source = DamageSource(red.id, red.card)

            // Both leave the state's rules content untouched; only one narrates a reason.
            val zero = dealDamage(state, source, Target.Permanent(warder), 0)
            val prevented = dealDamage(state, source, Target.Permanent(warder), 3)

            zero shouldBeSameInstanceAs state
            zero.events.shouldBeEmpty()
            prevented.creature("Warder").damageMarked shouldBe 0
            prevented.events
                .filterIsInstance<GameEvent.DamagePrevented>()
                .single()
                .amount shouldBe 3
        }

        "CR 613.10: no card grants a player protection, so damage to a player is never prevented here" {
            val state = withWarder()
            val red = state.creature("Redcap")
            val after = dealDamage(state, DamageSource(red.id, red.card), Target.Player(bob), 3)
            after.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            after.events.filterIsInstance<GameEvent.DamagePrevented>().shouldBeEmpty()
        }
    })
