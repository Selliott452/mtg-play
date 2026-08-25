package dev.mtgplay.acceptance.invariant

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.zone.ZoneId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentSetOf

/**
 * The [Invariant.MANA_ABILITY_ACTIVATION_SCOPE] suite (CR 602.5b). Each arm gets a handcrafted
 * violating residence that yields exactly that violation, plus clean-state coverage.
 *
 * Wall of Roots is the measured card because it is the only gauntlet card that prints "Activate only
 * once each turn" on a mana ability; Grizzly Bears stands in for every object that has no such
 * ability and must therefore never carry a record at all.
 */
class ManaAbilityActivationInvariantSpec :
    StringSpec({

        val definitions = MvpCards.definitions

        fun residence(
            zone: ZoneId,
            card: String,
            recorded: Set<Int>,
        ): ZoneResidence =
            ZoneResidence(
                zone = zone,
                obj =
                    GameObject(
                        id = ObjectId(1),
                        card = CardRef(card),
                        owner = PlayerId(0),
                        manaAbilitiesActivatedThisTurn = persistentSetOf<Int>().addingAll(recorded),
                    ),
            )

        "CR 602.5b: a battlefield Wall of Roots that has spent its once-each-turn activation is clean" {
            checkManaAbilityActivationScope(
                listOf(residence(ZoneId.Battlefield, "Wall of Roots", setOf(0))),
                definitions,
            ).shouldBeEmpty()
        }

        "CR 602.5b: an object with no record at all is clean, wherever it is" {
            listOf(ZoneId.Battlefield, ZoneId.Graveyard(PlayerId(0)), ZoneId.Exile).forEach { zone ->
                checkManaAbilityActivationScope(
                    listOf(residence(zone, "Grizzly Bears", emptySet())),
                    definitions,
                ).shouldBeEmpty()
            }
        }

        "CR 400.7: a record surviving a zone move is a violation" {
            // A zone move makes a new object with no history, so a graveyard card carrying the record
            // means some move copied state it should have dropped — the failure shape COUNTER_SCOPE's
            // second arm catches for counters, and just as silent.
            val violations =
                checkManaAbilityActivationScope(
                    listOf(residence(ZoneId.Graveyard(PlayerId(0)), "Wall of Roots", setOf(0))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().invariant shouldBe Invariant.MANA_ABILITY_ACTIVATION_SCOPE
            violations.single().detail.shouldContain("CR 400.7")
        }

        "CR 602.5b: a record naming an unrestricted printed ability is a violation" {
            // A Forest's only mana ability is unrestricted, so the engine writes nothing for it. A
            // record here means the executor and the availability filter disagree about which ability
            // was spent, and the Forest would silently stop producing for the rest of the turn.
            val violations =
                checkManaAbilityActivationScope(
                    listOf(residence(ZoneId.Battlefield, "Forest", setOf(0))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().detail.shouldContain("not restricted to one activation each turn")
        }

        "CR 602.5b: a record naming an ability the card does not print is a violation" {
            val violations =
                checkManaAbilityActivationScope(
                    listOf(residence(ZoneId.Battlefield, "Wall of Roots", setOf(3))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().detail.shouldContain("not printed on it")
        }
    })
