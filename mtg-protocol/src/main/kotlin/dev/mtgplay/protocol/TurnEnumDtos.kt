package dev.mtgplay.protocol

import dev.mtgplay.core.state.MulliganStage
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the turn-structure enums. Every mapping is an exhaustive `when` (no `else`).
 */

/** Wire form of [TurnPhase] (CR 500.1). */
@Serializable
enum class TurnPhaseDto { BEGINNING, PRECOMBAT_MAIN, COMBAT, POSTCOMBAT_MAIN, ENDING }

/** Wire form of [TurnStep] (CR 500.1). */
@Serializable
enum class TurnStepDto {
    UNTAP,
    UPKEEP,
    DRAW,
    BEGINNING_OF_COMBAT,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    COMBAT_DAMAGE,
    END_OF_COMBAT,
    END,
    CLEANUP,
}

/** Wire form of [MulliganStage] (CR 103.5). */
@Serializable
enum class MulliganStageDto { DECLARE, BOTTOM }

/** [TurnPhase] to its wire form. */
fun TurnPhase.toDto(): TurnPhaseDto =
    when (this) {
        TurnPhase.BEGINNING -> TurnPhaseDto.BEGINNING
        TurnPhase.PRECOMBAT_MAIN -> TurnPhaseDto.PRECOMBAT_MAIN
        TurnPhase.COMBAT -> TurnPhaseDto.COMBAT
        TurnPhase.POSTCOMBAT_MAIN -> TurnPhaseDto.POSTCOMBAT_MAIN
        TurnPhase.ENDING -> TurnPhaseDto.ENDING
    }

/** [TurnPhaseDto] back to the engine value. */
fun TurnPhaseDto.toDomain(): TurnPhase =
    when (this) {
        TurnPhaseDto.BEGINNING -> TurnPhase.BEGINNING
        TurnPhaseDto.PRECOMBAT_MAIN -> TurnPhase.PRECOMBAT_MAIN
        TurnPhaseDto.COMBAT -> TurnPhase.COMBAT
        TurnPhaseDto.POSTCOMBAT_MAIN -> TurnPhase.POSTCOMBAT_MAIN
        TurnPhaseDto.ENDING -> TurnPhase.ENDING
    }

/** [TurnStep] to its wire form. */
fun TurnStep.toDto(): TurnStepDto =
    when (this) {
        TurnStep.UNTAP -> TurnStepDto.UNTAP
        TurnStep.UPKEEP -> TurnStepDto.UPKEEP
        TurnStep.DRAW -> TurnStepDto.DRAW
        TurnStep.BEGINNING_OF_COMBAT -> TurnStepDto.BEGINNING_OF_COMBAT
        TurnStep.DECLARE_ATTACKERS -> TurnStepDto.DECLARE_ATTACKERS
        TurnStep.DECLARE_BLOCKERS -> TurnStepDto.DECLARE_BLOCKERS
        TurnStep.COMBAT_DAMAGE -> TurnStepDto.COMBAT_DAMAGE
        TurnStep.END_OF_COMBAT -> TurnStepDto.END_OF_COMBAT
        TurnStep.END -> TurnStepDto.END
        TurnStep.CLEANUP -> TurnStepDto.CLEANUP
    }

/** [TurnStepDto] back to the engine value. */
fun TurnStepDto.toDomain(): TurnStep =
    when (this) {
        TurnStepDto.UNTAP -> TurnStep.UNTAP
        TurnStepDto.UPKEEP -> TurnStep.UPKEEP
        TurnStepDto.DRAW -> TurnStep.DRAW
        TurnStepDto.BEGINNING_OF_COMBAT -> TurnStep.BEGINNING_OF_COMBAT
        TurnStepDto.DECLARE_ATTACKERS -> TurnStep.DECLARE_ATTACKERS
        TurnStepDto.DECLARE_BLOCKERS -> TurnStep.DECLARE_BLOCKERS
        TurnStepDto.COMBAT_DAMAGE -> TurnStep.COMBAT_DAMAGE
        TurnStepDto.END_OF_COMBAT -> TurnStep.END_OF_COMBAT
        TurnStepDto.END -> TurnStep.END
        TurnStepDto.CLEANUP -> TurnStep.CLEANUP
    }

/** [MulliganStage] to its wire form. */
fun MulliganStage.toDto(): MulliganStageDto =
    when (this) {
        MulliganStage.DECLARE -> MulliganStageDto.DECLARE
        MulliganStage.BOTTOM -> MulliganStageDto.BOTTOM
    }

/** [MulliganStageDto] back to the engine value. */
fun MulliganStageDto.toDomain(): MulliganStage =
    when (this) {
        MulliganStageDto.DECLARE -> MulliganStage.DECLARE
        MulliganStageDto.BOTTOM -> MulliganStage.BOTTOM
    }
