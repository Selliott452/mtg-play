package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.DeathReplacement
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.damageCannotBePreventedThisTurn
import dev.mtgplay.rules.effect.dealDamageThenExileIfItWouldDie
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exileInsteadOfDyingThisTurn
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.effect.preventDamageFromColorThisTurn
import dev.mtgplay.rules.engine.SbaOutcome
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.performStateBasedActions
import dev.mtgplay.rules.engine.sacrificeControlledPermanent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The **delayed death-replacement store** (`W9-D`, CR 614.1a, CR 603.7a): the effect that lives in it and
 * the four interception points that read it.
 *
 * Pinned by hand for `PreventionStoreSpec`'s reason. `EnumerationProbe` structurally cannot catch a bug
 * here: a death replacement changes an *outcome* — which zone a permanent ends in — and never an option
 * set, so a replacement that failed to apply would leave every enumerated action exactly where it was and
 * quietly hand a graveyard deck back a card it should never have had.
 *
 * The properties that matter are the ones a card composing this depends on. CR 700.4's "dies" is **every**
 * battlefield-to-graveyard move, so all four are exercised separately — missing one is the whole failure
 * mode, and three of the four are reached by cards the rider never mentions. Two more corners: the
 * replacement outlives the damage that created it but not the turn (CR 514.2), and an event that is not a
 * death is not touched.
 */
class DeathReplacementSpec :
    StringSpec({
        val torch = CardRef("Torch the Tower")
        val redSource = DamageSource(objectId = null, card = CardRef("Redcap"))

        // "Bear" is a plain 2/2, "Ogre" a 3/3, "Ward Aura" an Aura the fixtures can attach.
        fun board(): GameState =
            keywordState(
                listOf(
                    combatObject(0, "Bear", alice),
                    combatObject(1, "Ogre", bob),
                ),
            )

        fun bearId(state: GameState) = state.creatureOf("Bear", alice).id

        "CR 614.1a: a permanent that would die to lethal damage is exiled instead" {
            val state = board()
            val bear = bearId(state)
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)
            // Something else finishes it: the rider does not care what kills it (CR 700.4).
            val killed = dealDamageThenExileIfItWouldDie(watched, redSource, bear, 2)

            val after = (performStateBasedActions(killed) as SbaOutcome.Continued).state
            after.sharedZones.battlefield.none { it.id == bear } shouldBe true
            after.sharedZones.exile.map { it.card } shouldContain CardRef("Bear")
            after.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            // CR 614.6: the replaced event never happens, so nothing narrates a death.
            after.events.none { it is GameEvent.CreatureDied } shouldBe true
            after.events shouldContain
                GameEvent.PermanentExiled(
                    bear,
                    CardRef("Bear"),
                    after.sharedZones.exile
                        .last()
                        .id,
                )
        }

        "CR 700.4: a destroy effect is a death too, so a watched permanent is exiled rather than destroyed" {
            val state = board()
            val bear = bearId(state)
            val destroyed = destroy(exileInsteadOfDyingThisTurn(state, listOf(bear), torch), bear)

            destroyed.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            destroyed.sharedZones.exile.map { it.card } shouldContain CardRef("Bear")
            // CR 701.7a's own event does not fire: the destruction was replaced before it happened.
            destroyed.events.none { it is GameEvent.PermanentDestroyed } shouldBe true
        }

        "CR 700.4: a sacrifice is a death, so sacrificing a watched permanent exiles it" {
            val state = board()
            val bear = bearId(state)
            val sacrificed = sacrificeControlledPermanent(exileInsteadOfDyingThisTurn(state, listOf(bear), torch), bear)

            sacrificed.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            sacrificed.sharedZones.exile.map { it.card } shouldContain CardRef("Bear")
        }

        "CR 704.5m: an Aura that falls off a watched board is caught too" {
            // The Aura itself is what is watched here — a Torch may damage anything, and an Aura that
            // then falls off its dead creature dies (CR 700.4) exactly as a creature does.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Bear", alice),
                        combatObject(1, "Ward Aura", alice, attachedTo = 0),
                    ),
                )
            val aura = state.creatureOf("Ward Aura", alice).id
            val bear = state.creatureOf("Bear", alice).id
            val watched = exileInsteadOfDyingThisTurn(state, listOf(aura), torch)
            // Kill the creature the Aura is on; the Aura then has nothing legal to be attached to.
            val killed = destroy(watched, bear)
            val after = (performStateBasedActions(killed) as SbaOutcome.Continued).state

            after.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Bear")
            after.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldNotContain CardRef("Ward Aura")
            after.sharedZones.exile.map { it.card } shouldContain CardRef("Ward Aura")
        }

        "CR 614.1a: an unwatched permanent dying alongside a watched one is unaffected" {
            val state = board()
            val bear = bearId(state)
            val ogre = state.creatureOf("Ogre", bob).id
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)
            val after = destroy(destroy(watched, bear), ogre)

            after.sharedZones.exile.map { it.card } shouldContain CardRef("Bear")
            after.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContain CardRef("Ogre")
        }

        "CR 611.2: the replacement is stored as until-end-of-turn, stamped with the turn that made it" {
            val state = board()
            val bear = bearId(state)
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)

            val stored = watched.deathReplacements.single()
            stored.effect shouldBe DeathReplacement.ExileInstead
            stored.affected shouldBe setOf(bear)
            stored.duration shouldBe EffectDuration.UntilEndOfTurn
            stored.createdOnTurn shouldBe TURN_NUMBER
            watched.events shouldContain GameEvent.DeathReplacementCreated(torch, listOf(bear))
        }

        "CR 514.2: the cleanup turn-based action ends every stored death replacement" {
            val state = board()
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bearId(state)), torch)

            cleanupRemoveDamageAndEndEffects(watched).deathReplacements.shouldBeEmpty()
        }

        "CR 614.1a: a replacement watching nothing is not created at all" {
            val state = board()
            exileInsteadOfDyingThisTurn(state, emptyList(), torch) shouldBe state
        }

        "CR 615.6: prevented damage is not dealt, so the permanent is never watched" {
            val state = preventDamageFromColorThisTurn(board(), Color.RED, CardRef("Prismatic Strands"))
            val bear = bearId(state)
            val hit = dealDamageThenExileIfItWouldDie(state, redSource, bear, 2)

            hit.deathReplacements.shouldBeEmpty()
            // …and the creature therefore dies normally when something else kills it.
            destroy(hit, bear)
                .players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Bear")
        }

        "CR 615.9: with prevention turned off the same damage does watch the permanent" {
            val state =
                damageCannotBePreventedThisTurn(
                    preventDamageFromColorThisTurn(board(), Color.RED, CardRef("Prismatic Strands")),
                    CardRef("Flaring Pain"),
                )
            val bear = bearId(state)
            val hit = dealDamageThenExileIfItWouldDie(state, redSource, bear, 2)

            hit.deathReplacements.single().affected shouldBe setOf(bear)
        }

        "CR 120.8: zero damage is not dealt, so nothing is watched" {
            val state = board()
            dealDamageThenExileIfItWouldDie(state, redSource, bearId(state), 0).deathReplacements.shouldBeEmpty()
        }

        "CR 614.5: the entry is not consumed — the object it caught can never present a second event" {
            val state = board()
            val bear = bearId(state)
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)
            // The replacement is still there after the death it caught: CR 614.5 is satisfied by the
            // object, not by consuming the entry.
            destroy(watched, bear).deathReplacements.single().affected shouldBe setOf(bear)
        }

        "CR 701.3a: an exile is not a death, so a watched permanent exiled twice is not double-moved" {
            val state = board()
            val bear = bearId(state)
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)
            val exiled = exilePermanent(watched, bear)

            exiled.sharedZones.exile.map { it.card } shouldContain CardRef("Bear")
            // The old battlefield id is gone; a second attempt at it fails loudly rather than
            // silently exiling an object that no longer exists (CR 400.7).
            shouldThrow<IllegalArgumentException> { destroy(exiled, bear) }
        }

        "CR 400.7: a returning permanent is a new object and is no longer watched" {
            val state = board()
            val bear = bearId(state)
            val watched = exileInsteadOfDyingThisTurn(state, listOf(bear), torch)
            val stored = watched.deathReplacements.single()

            // The set names the *battlefield* id. The id counter is strictly monotonic, so no later
            // object can ever reuse it — which is the whole reason an id set is the correct encoding.
            stored.affected.forEach { it.value shouldBe bear.value }
            (watched.nextObjectId > bear.value) shouldBe true
            watched.deathReplacements.firstOrNull().shouldNotBeNull()
        }
    })
