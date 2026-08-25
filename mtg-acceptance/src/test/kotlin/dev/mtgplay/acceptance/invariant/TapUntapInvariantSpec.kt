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
 * The two `FW-TAPUNTAP` invariant suites: [Invariant.ACTIVATED_ABILITY_ACTIVATION_SCOPE] (CR 602.5b)
 * and [Invariant.SKIPS_NEXT_UNTAP_SCOPE] (CR 502.2). Each arm gets a handcrafted violating residence
 * that yields exactly that violation, plus clean-state coverage — the shape
 * [ManaAbilityActivationInvariantSpec] established for the sibling record.
 *
 * Quirion Ranger is the measured card because it is the only gauntlet card printing "Activate only
 * once each turn" on a **non-mana** activated ability; Grizzly Bears stands in for every object that
 * has no such ability and must therefore never carry a record at all.
 */
class TapUntapInvariantSpec :
    StringSpec({

        val definitions = MvpCards.definitions

        fun residence(
            zone: ZoneId,
            card: String,
            recorded: Set<Int> = emptySet(),
            asleep: Boolean = false,
        ): ZoneResidence =
            ZoneResidence(
                zone = zone,
                obj =
                    GameObject(
                        id = ObjectId(1),
                        card = CardRef(card),
                        owner = PlayerId(0),
                        activatedAbilitiesActivatedThisTurn = persistentSetOf<Int>().addingAll(recorded),
                        skipsNextUntapStep = asleep,
                    ),
            )

        // ---- CR 602.5b: the non-mana per-turn activation record ------------------------------------

        "CR 602.5b: a battlefield Quirion Ranger that has spent its once-each-turn activation is clean" {
            checkActivatedAbilityActivationScope(
                listOf(residence(ZoneId.Battlefield, "Quirion Ranger", recorded = setOf(0))),
                definitions,
            ).shouldBeEmpty()
        }

        "CR 602.5b: an object with no record at all is clean, wherever it is" {
            listOf(ZoneId.Battlefield, ZoneId.Graveyard(PlayerId(0)), ZoneId.Exile).forEach { zone ->
                checkActivatedAbilityActivationScope(
                    listOf(residence(zone, "Grizzly Bears")),
                    definitions,
                ).shouldBeEmpty()
            }
        }

        "CR 400.7: an activated-ability record surviving a zone move is a violation" {
            // A bounced-and-recast Ranger is a new object whose new ability has not been activated;
            // a record that crossed the move would silently un-activatable it for the rest of the turn.
            val violations =
                checkActivatedAbilityActivationScope(
                    listOf(residence(ZoneId.Graveyard(PlayerId(0)), "Quirion Ranger", recorded = setOf(0))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().invariant shouldBe Invariant.ACTIVATED_ABILITY_ACTIVATION_SCOPE
            violations.single().detail.shouldContain("CR 400.7")
        }

        "CR 602.5b: a record naming an unrestricted printed ability is a violation" {
            // Harrier Strix's "{2}{U}: Draw a card, then discard a card" carries no restriction, so the
            // engine writes nothing for it; a record here would silently retire the ability for the turn.
            val violations =
                checkActivatedAbilityActivationScope(
                    listOf(residence(ZoneId.Battlefield, "Harrier Strix", recorded = setOf(0))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().detail.shouldContain("not restricted to one activation each turn")
        }

        "CR 602.5b: a record naming an ability the card does not print is a violation" {
            val violations =
                checkActivatedAbilityActivationScope(
                    listOf(residence(ZoneId.Battlefield, "Quirion Ranger", recorded = setOf(3))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().detail.shouldContain("not printed on it")
        }

        "CR 602.5b: the two per-turn records index different lists and are checked separately" {
            // Wall of Roots' restricted ability is a *mana* ability at index 0. Recording index 0 in the
            // activated-ability set is therefore a violation even though index 0 is a real restricted
            // ability on the same card — which is the whole reason the two records are not one set.
            val violations =
                checkActivatedAbilityActivationScope(
                    listOf(residence(ZoneId.Battlefield, "Wall of Roots", recorded = setOf(0))),
                    definitions,
                )
            violations.size shouldBe 1
            violations.single().detail.shouldContain("not printed on it")
        }

        // ---- CR 502.2: the doesn't-untap marker ----------------------------------------------------

        "CR 502.2: a battlefield permanent carrying the doesn't-untap marker is clean" {
            checkSkipsNextUntapScope(
                listOf(residence(ZoneId.Battlefield, "Grizzly Bears", asleep = true)),
            ).shouldBeEmpty()
        }

        "CR 110.5: the doesn't-untap marker outside the battlefield is a violation" {
            // Tapped status is battlefield-only, and so is anything qualifying it. A marker that
            // survived a bounce would hold down a creature that had, in rules terms, never been Slept —
            // invisible until an untap step silently did nothing.
            listOf(ZoneId.Graveyard(PlayerId(0)), ZoneId.Exile, ZoneId.Hand(PlayerId(0))).forEach { zone ->
                val violations = checkSkipsNextUntapScope(listOf(residence(zone, "Grizzly Bears", asleep = true)))
                violations.size shouldBe 1
                violations.single().invariant shouldBe Invariant.SKIPS_NEXT_UNTAP_SCOPE
                violations.single().detail.shouldContain("CR 110.5")
            }
        }

        "CR 502.2: an unmarked object is clean in every zone" {
            listOf(ZoneId.Battlefield, ZoneId.Graveyard(PlayerId(0)), ZoneId.Exile).forEach { zone ->
                checkSkipsNextUntapScope(listOf(residence(zone, "Grizzly Bears"))).shouldBeEmpty()
            }
        }
    })
