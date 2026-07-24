package dev.mtgplay.rules

import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.collections.immutable.persistentListOf

/**
 * The P7.2 regression (CR 104.2a / CR 603.3b): [pendingRequestOf] must return `null` for a terminal
 * state — its own contract is "null if the state is not a pause point", and a finished game is not a
 * pause point. The exact case the reference server hit: a player lost to a state-based action while a
 * single fired-but-unplaced trigger was still dangling in [dev.mtgplay.core.state.GameState.pendingTriggers].
 * Before the fix, [pendingRequestOf] ignored terminality, saw the lone trigger, and tried to build an
 * `OrderTriggers` request from it — throwing, because a single trigger is placed automatically and
 * never ordered (CR 603.3b). The engine now short-circuits terminal states, so no throw and no request.
 */
class TerminalStatePendingRequestSpec :
    StringSpec({

        "CR 104.2a/CR 603.3b: pendingRequestOf is null on a terminal state carrying a lone unplaced trigger" {
            // alice at 0 life makes a player-loss state-based action applicable (CR 704.5a): the game is over.
            val ability =
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { s, _ -> s },
                )
            val danglingTrigger = PendingTrigger(ObjectId(0), CardRef("Fixture Trigger"), bob, ability)
            val terminal =
                twoPlayerState(
                    turn = Turn(alice, 5, TurnPhase.POSTCOMBAT_MAIN, null),
                    aliceState = playerWithZones(life = 0),
                    bobState = playerWithZones(life = STARTING_LIFE),
                    nextObjectId = 1,
                ).copy(pendingTriggers = persistentListOf(danglingTrigger))

            // Precondition: this really is a terminal state (a player-loss SBA is applicable).
            applicableStateBasedActions(terminal)
                .any { it is StateBasedAction.PlayerLoses }
                .shouldBeTrue()

            // The fix: no throw from the lone trigger, and no pending request.
            pendingRequestOf(terminal).shouldBeNull()
        }

        "ADR-007/ADR-008: the per-seat view of a terminal state reports no pending decision, both seats" {
            // The path the reference server exercises for its GameOver message: viewFor derives
            // pendingDecision via pendingRequestOf, so the terminal short-circuit makes both seats' views
            // report no decision — no server-side workaround needed to strip a moot pending field.
            val ability =
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { s, _ -> s },
                )
            val danglingTrigger = PendingTrigger(ObjectId(0), CardRef("Fixture Trigger"), alice, ability)
            val terminal =
                twoPlayerState(
                    turn = Turn(alice, 5, TurnPhase.POSTCOMBAT_MAIN, null),
                    aliceState = playerWithZones(life = 0),
                    bobState = playerWithZones(life = STARTING_LIFE),
                    nextObjectId = 1,
                ).copy(pendingTriggers = persistentListOf(danglingTrigger))

            viewFor(terminal, alice).pendingDecision.shouldBeNull()
            viewFor(terminal, bob).pendingDecision.shouldBeNull()
        }
    })
