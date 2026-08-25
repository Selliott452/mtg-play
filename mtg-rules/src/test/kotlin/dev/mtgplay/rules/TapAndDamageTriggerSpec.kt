package dev.mtgplay.rules

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.tapPermanent
import dev.mtgplay.rules.effect.untapPermanent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

/**
 * `W8-C` — the two trigger conditions nothing in the engine watched for before this packet, and the
 * disjunction combinator that lets one printed ability carry both:
 * [TriggerCondition.EnchantedPermanentBecomesTapped] (CR 701.20a),
 * [TriggerCondition.EnchantedPermanentIsDealtDamage] (CR 120.3d), and [TriggerCondition.AnyOf]
 * (CR 603.2). Cryoshatter is their only client in `mtg-cards`; the fixtures here are "Frostbind" (both
 * patterns) and "Tapwatch" (the tap pattern alone).
 *
 * The properties worth pinning are the *edges*, because a trigger that silently never fires leaves no
 * trace to check against: which ways of becoming tapped count, which do not, and that a disjunction is
 * a real match rather than a blanket one.
 */
class TapAndDamageTriggerSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 701.20a: a tap effect on an enchanted permanent fires the Aura's trigger, naming it as subject" {
            val board = enchantedBear()
            val bear = board.creatureOf("Bear", alice)
            val aura = board.creatureOf("Frostbind", alice)
            val tapped = tapPermanent(board, bear.id)

            tapped.pendingTriggers shouldHaveSize 1
            with(tapped.pendingTriggers.single()) {
                sourceId shouldBe aura.id
                sourceCard shouldBe CardRef("Frostbind")
                // CR 603.10: "destroy **it**" acts on the enchanted permanent, captured as the trigger fires.
                subject shouldBe bear.id
            }
        }

        "CR 701.20a: tapping an already-tapped permanent is not an event, so nothing fires" {
            val board = enchantedBear(bearTapped = true)
            tapPermanent(board, board.creatureOf("Bear", alice).id)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 701.21b: untapping fires nothing — no condition in the pool watches for it" {
            val board = enchantedBear(bearTapped = true)
            untapPermanent(board, board.creatureOf("Bear", alice).id)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 611.2c: an Aura attached to something else does not fire when a different permanent taps" {
            val board = enchantedBear(extraCreature = "Ogre")
            tapPermanent(board, board.creatureOf("Ogre", alice).id)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 120.3d: damage marked on an enchanted permanent fires the Aura's trigger" {
            val board = enchantedBear()
            val bear = board.creatureOf("Bear", alice)
            val damaged = dealDamage(board, fixtureDamageSource, Target.Permanent(bear.id), 1)

            damaged.pendingTriggers shouldHaveSize 1
            damaged.pendingTriggers
                .single()
                .subject shouldBe bear.id
        }

        "CR 120.8: zero damage is not dealt, so it fires nothing" {
            val board = enchantedBear()
            dealDamage(board, fixtureDamageSource, Target.Permanent(board.creatureOf("Bear", alice).id), 0)
                .pendingTriggers
                .shouldBeEmpty()
        }

        "CR 603.2: a disjunctive condition matches either pattern; a single-pattern ability matches only its own" {
            // "Tapwatch" declares the tap half alone. It fires on a tap...
            val tapBoard = enchantedBear(auraName = "Tapwatch")
            tapPermanent(tapBoard, tapBoard.creatureOf("Bear", alice).id)
                .pendingTriggers
                .shouldHaveSize(1)
            // ...and stays silent on damage, which the disjunctive "Frostbind" does not.
            val damageBoard = enchantedBear(auraName = "Tapwatch")
            dealDamage(
                damageBoard,
                fixtureDamageSource,
                Target.Permanent(damageBoard.creatureOf("Bear", alice).id),
                1,
            ).pendingTriggers.shouldBeEmpty()
        }

        "CR 508.1f: declaring an enchanted creature as an attacker taps it, fires the trigger, and destroys it" {
            val attacking =
                keywordState(
                    battlefield =
                        listOf(
                            combatObject(0, "Bear", alice),
                            combatObject(1, "Frostbind", alice, attachedTo = 0),
                        ),
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
                )
            val request = pausedRequestOf<DecisionRequest.DeclareAttackers>(attacking)
            val declared = engine.advance(attacking, Decision.MultiSelect(request.id, listOf(0)))
            // The attacker is tapped (CR 508.1f) and the trigger fired against that flip.
            val afterDeclaration = declared.pausedState
            afterDeclaration.creatureOf("Bear", alice).tapped shouldBe true

            // Let the trigger reach the stack and resolve: the attacker dies to its own Aura.
            val resolved = engine.passPriorityRound(declared).pausedState
            resolved.sharedZones.battlefield
                .none { it.card == CardRef("Bear") }
                .shouldBe(true)
        }

        "CR 605.1a: tapping a land for mana fires a 'becomes tapped' trigger on the Aura enchanting it" {
            // An Aura on a land is not something the pool prints, but the tap site is the mana one and
            // that is what this pins: every way of becoming tapped funnels through one announcement.
            val board =
                keywordState(
                    battlefield =
                        listOf(
                            combatObject(0, "Mountain", alice),
                            combatObject(1, "Frostbind", alice, attachedTo = 0),
                        ),
                )
            val mountain = board.sharedZones.battlefield.first { it.card == CardRef("Mountain") }
            tapPermanent(board, mountain.id).pendingTriggers shouldHaveSize 1
        }
    })

/** The damage source the direct-damage cases deal from (CR 120.1); an off-battlefield fixture card. */
private val fixtureDamageSource = DamageSource(objectId = null, card = CardRef("Fixture Bolt"))

/**
 * A board with alice's "Bear" enchanted by [auraName], optionally already tapped and optionally beside
 * an unenchanted [extraCreature] — the three axes the tap and damage cases vary.
 */
private fun enchantedBear(
    auraName: String = "Frostbind",
    bearTapped: Boolean = false,
    extraCreature: String? = null,
): GameState {
    val battlefield =
        listOfNotNull(
            combatObject(0, "Bear", alice),
            combatObject(1, auraName, alice, attachedTo = 0),
            extraCreature?.let { combatObject(2, it, alice) },
        )
    val board = keywordState(battlefield)
    if (!bearTapped) return board
    return board.copy(
        sharedZones =
            board.sharedZones.copy(
                battlefield =
                    board.sharedZones.battlefield
                        .map { if (it.card == CardRef("Bear")) it.copy(tapped = true) else it }
                        .toPersistentList(),
            ),
    )
}
