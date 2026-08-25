package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.effectiveKeywords
import dev.mtgplay.rules.engine.eligibleAttackers
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

/*
 * The three keywords `FW-COUNTERS` added, each tested at the site that decides the thing it changes
 * rather than at the enum. A keyword whose only evidence is that it appears in a set is a bare enum
 * member; these specs are the difference.
 *
 * Attacking and blocking are tested through the *enumerated* action space (ADR-005) — the attacker
 * list the engine offers, the block pairings in a real `DeclareBlockers` request — because that is
 * what a keyword changes here. The mana-usability half of haste (CR 702.10c) needs a mana-source
 * fixture and lives in HasteManaSourceSpec.
 */
class HasteDefenderReachSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // --- Haste: CR 702.10b, the attack half ---

        "CR 702.10b: a summoning-sick creature with haste is offered as an attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Hasty", summoningSick = true)))
            eligibleAttackers(state).map { it.card.name } shouldContainExactly listOf("Hasty")
        }

        "CR 302.6: a summoning-sick creature without haste is still not offered as an attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Bear", summoningSick = true)))
            eligibleAttackers(state) shouldBe emptyList()
        }

        "CR 702.10b: haste lifts only the CR 302.6 bar, not the CR 508.1a untapped requirement" {
            val state = attackStep(aliceField = listOf(Combatant("Hasty", tapped = true, summoningSick = true)))
            eligibleAttackers(state) shouldBe emptyList()
        }

        "CR 702.10b: haste granted by an Aura (CR 613.1f layer 6) lifts summoning sickness" {
            // Read through the effective-keyword seam, so a grant works without eligibleAttackers
            // knowing the keyword was not printed.
            val state = withAura(attackStep(aliceField = listOf(Combatant("Bear", summoningSick = true))), "Haste Aura")
            eligibleAttackers(state).map { it.card.name } shouldContainExactly listOf("Bear")
        }

        "CR 122.1b: a haste counter lifts summoning sickness through layer 6" {
            val state =
                attackStep(
                    aliceField =
                        listOf(
                            Combatant(
                                "Bear",
                                summoningSick = true,
                                counters = mapOf(Counter.KeywordCounter(Keyword.HASTE) to 1),
                            ),
                        ),
                )
            effectiveKeywords(state, ObjectId(0)) shouldBe setOf(Keyword.HASTE)
            eligibleAttackers(state).map { it.card.name } shouldContainExactly listOf("Bear")
        }

        // --- Defender: CR 702.3b ---

        "CR 702.3b: a creature with defender is never offered as an attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Bulwark"), Combatant("Bear")))
            eligibleAttackers(state).map { it.card.name } shouldContainExactly listOf("Bear")
        }

        "CR 702.3b: defender bars the attack even when haste has lifted summoning sickness" {
            // The pair that proves the two clauses are independent rather than one restated: Sentry is
            // ready to attack in every respect haste governs, and still cannot.
            val state = attackStep(aliceField = listOf(Combatant("Sentry", summoningSick = true)))
            eligibleAttackers(state) shouldBe emptyList()
        }

        "CR 702.3b: defender granted by an Aura (CR 613.1f layer 6) bars a creature that could otherwise attack" {
            val state = withAura(attackStep(aliceField = listOf(Combatant("Bear"))), "Wall Aura")
            eligibleAttackers(state) shouldBe emptyList()
        }

        "CR 702.3c: a creature with defender still blocks normally" {
            // Defender is an attack bar and nothing else; it is not a blocking restriction.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ogre")),
                    bobField = listOf(Combatant("Bulwark")),
                )
            val request = engine.toDeclareBlockers(state, "Ogre").pending<DecisionRequest.DeclareBlockers>()
            request.blockPairs() shouldContainExactly listOf("Bulwark" to "Ogre")
        }

        // --- Reach: CR 702.17b ---

        "CR 702.17b: a creature with reach may block a creature with flying" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Flyer")),
                    bobField = listOf(Combatant("Reacher"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Flyer").pending<DecisionRequest.DeclareBlockers>()

            // The reaching blocker is offered; the plain ground Bear is not (CR 702.9b).
            request.blockPairs() shouldContainExactly listOf("Reacher" to "Flyer")
        }

        "CR 702.17b: reach is a permission, not a restriction — a reaching creature blocks a ground attacker" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ogre")),
                    bobField = listOf(Combatant("Reacher"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Ogre").pending<DecisionRequest.DeclareBlockers>()
            request.blockPairs() shouldContainExactlyInAnyOrder listOf("Reacher" to "Ogre", "Bear" to "Ogre")
        }

        "CR 702.17b: reach does not satisfy 'can't be blocked except by creatures with flying'" {
            // Silhana Ledgewalker's line names flying literally. Before reach existed, that restriction
            // and CR 702.9b shared one predicate; sharing it now would silently let Reacher through,
            // which is why canBlock splits them.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Skulker")),
                    bobField = listOf(Combatant("Reacher"), Combatant("Flyer")),
                )
            val request = engine.toDeclareBlockers(state, "Skulker").pending<DecisionRequest.DeclareBlockers>()
            request.blockPairs() shouldContainExactly listOf("Flyer" to "Skulker")
        }

        "CR 702.17b: reach granted by an Aura (CR 613.1f layer 6) lets a ground creature block a flyer" {
            val base =
                attackStep(
                    aliceField = listOf(Combatant("Flyer")),
                    bobField = listOf(Combatant("Bear")),
                )
            val state = withAura(base, "Reach Aura", attachedTo = 1, owner = bob)
            val request = engine.toDeclareBlockers(state, "Flyer").pending<DecisionRequest.DeclareBlockers>()
            request.blockPairs() shouldContainExactly listOf("Bear" to "Flyer")
        }
    })

/** An id well clear of the handcrafted field's allocations, for an Aura appended after the fact. */
private const val AURA_ID: Long = 90

/** [state] with [auraName] on the battlefield attached to the object with id [attachedTo]. */
private fun withAura(
    state: GameState,
    auraName: String,
    attachedTo: Long = 0,
    owner: PlayerId = alice,
): GameState {
    val aura =
        GameObject(
            id = ObjectId(AURA_ID),
            card = CardRef(auraName),
            owner = owner,
            attachedTo = ObjectId(attachedTo),
            summoningSick = false,
        )
    return state.copy(
        sharedZones = state.sharedZones.copy(battlefield = (state.sharedZones.battlefield + aura).toPersistentList()),
        nextObjectId = maxOf(state.nextObjectId, AURA_ID + 1),
    )
}
