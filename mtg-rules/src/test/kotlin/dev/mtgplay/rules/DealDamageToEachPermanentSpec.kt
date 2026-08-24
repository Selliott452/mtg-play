package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.dealDamageToEachPermanent
import dev.mtgplay.rules.effect.isCreaturePermanent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The sweep-damage effect primitive (CR 120): one source damages every battlefield permanent an
 * affected-set predicate accepts. Damage lands on permanents as *marked* damage (CR 120.3d), the
 * affected set is fixed as the effect is applied (CR 608.2), and the predicate — the card's printed
 * qualifier — never has to know how the engine decides what a creature is ([isCreaturePermanent]).
 *
 * The `mtg-rules`-names-no-card rule holds: these are synthetic battlefield fixtures, not a named card.
 */
class DealDamageToEachPermanentSpec :
    StringSpec({
        // A board with two creatures under different seats, a non-creature permanent (an Aura attached
        // to one of them), and an object with no definition at all (inert, CR 109.3).
        fun board() =
            keywordState(
                battlefield =
                    listOf(
                        combatObject(0, "Bear", alice),
                        combatObject(1, "Hex Aura", alice, attachedTo = 0),
                        combatObject(2, "Ogre", bob),
                        combatObject(3, "Mountain", bob),
                    ),
            )

        "CR 120.3d: sweep damage is marked on every accepted creature and on no other permanent" {
            val swept = dealDamageToEachPermanent(board(), 2, ::isCreaturePermanent)
            swept.creatureOf("Bear", alice).damageMarked shouldBe 2
            swept.creatureOf("Ogre", bob).damageMarked shouldBe 2
            // The Aura is a permanent but not a creature; the undefined object is inert (CR 109.3).
            swept.creatureOf("Hex Aura", alice).damageMarked shouldBe 0
            swept.creatureOf("Mountain", bob).damageMarked shouldBe 0
        }

        "CR 120.3d: sweep damage marks permanents and never changes a life total" {
            val swept = dealDamageToEachPermanent(board(), 2, ::isCreaturePermanent)
            swept.events.filterIsInstance<GameEvent.LifeChanged>().shouldBeEmpty()
            swept.players.getValue(alice).life shouldBe STARTING_LIFE
            swept.players.getValue(bob).life shouldBe STARTING_LIFE
        }

        "CR 120.6: one source's sweep deals its damage once per recipient, simultaneously" {
            val swept = dealDamageToEachPermanent(board(), 2, ::isCreaturePermanent)
            swept.events.filterIsInstance<GameEvent.DamageDealt>().map { it.recipient } shouldContainExactly
                listOf(
                    Target.Permanent(swept.creatureOf("Bear", alice).id),
                    Target.Permanent(swept.creatureOf("Ogre", bob).id),
                )
        }

        "CR 608.2: the predicate narrows the affected set — a controller qualifier spares the rest" {
            val swept =
                dealDamageToEachPermanent(board(), 3) { state, obj ->
                    isCreaturePermanent(state, obj) && obj.owner == bob
                }
            swept.creatureOf("Ogre", bob).damageMarked shouldBe 3
            swept.creatureOf("Bear", alice).damageMarked shouldBe 0
        }

        "CR 608.2: the affected set is fixed from the state the effect began in" {
            // The predicate accepts only undamaged creatures. Both qualify at the start, so both are
            // damaged: re-deriving the set per recipient would have spared the second one.
            val swept =
                dealDamageToEachPermanent(board(), 1) { state, obj ->
                    isCreaturePermanent(state, obj) && obj.damageMarked == 0
                }
            swept.creatureOf("Bear", alice).damageMarked shouldBe 1
            swept.creatureOf("Ogre", bob).damageMarked shouldBe 1
        }

        "CR 120.8: sweeping zero damage is not dealt at all — no marks, no events" {
            val start = board()
            val untouched = dealDamageToEachPermanent(start, 0, ::isCreaturePermanent)
            untouched shouldBeSameInstanceAs start
            untouched.events.shouldBeEmpty()
        }

        "CR 120: a negative sweep amount is an error, never silently clamped" {
            shouldThrow<IllegalArgumentException> { dealDamageToEachPermanent(board(), -1, ::isCreaturePermanent) }
        }

        "CR 120: a predicate that accepts nothing changes nothing" {
            val start = board()
            val untouched = dealDamageToEachPermanent(start, 4) { _, _ -> false }
            untouched.sharedZones.battlefield.map { it.damageMarked } shouldContainExactly listOf(0, 0, 0, 0)
            untouched.events.shouldBeEmpty()
        }

        "CR 302.1: only a creature-typed permanent is a creature; an undefined object is inert" {
            val state = board()
            isCreaturePermanent(state, state.creatureOf("Bear", alice)) shouldBe true
            isCreaturePermanent(state, state.creatureOf("Hex Aura", alice)) shouldBe false
            isCreaturePermanent(state, state.creatureOf("Mountain", bob)) shouldBe false
        }
    })
