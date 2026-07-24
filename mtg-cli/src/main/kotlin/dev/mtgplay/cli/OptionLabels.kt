package dev.mtgplay.cli

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.rules.decision.PriorityOption

/*
 * How one priority-window option (CR 117.1) reads as text: the cast/play/activate the player may
 * take, with the meaning the P6.3 corpus brief asks for - a cast shows its source zone and the
 * permission it uses (flashback vs escape from the graveyard, plot vs madness from exile).
 */

/** A priority option's menu label (CR 117.1): pass, cast a spell, play a land, plot, or activate. */
fun priorityOptionLabel(option: PriorityOption): String =
    when (option) {
        PriorityOption.Pass -> "Pass"
        is PriorityOption.CastSpell -> "Cast ${option.card.name}${castVia(option.source, option.permission)}"
        is PriorityOption.PlayLand -> "Play land ${option.card.name}"
        is PriorityOption.PlotCard -> "Plot ${option.card.name} (exile face-up; cast free later)"
        is PriorityOption.ActivateAbility ->
            "Activate ability ${option.abilityIndex + 1} of ${option.card.name} (from ${scopeName(option.scope)})"
    }

/** The " (from ...)" clause of a cast: its source zone and the permission it is cast with, or "". */
private fun castVia(
    source: CastSource,
    permission: CastingPermission?,
): String {
    if (permission == null && source == CastSource.HAND) return ""
    val how = permission?.let { permissionName(it) } ?: sourceName(source)
    return " (via $how from ${sourceName(source)})"
}

/** The human name of a casting permission (docs/decklists.md): flashback, escape, madness, plot, ... */
private fun permissionName(permission: CastingPermission): String =
    when (permission) {
        is CastingPermission.Madness -> "madness ${permission.cost.render()}"
        is CastingPermission.Flashback -> "flashback ${permission.cost.render()}"
        is CastingPermission.Escape -> "escape ${permission.cost.render()}, exile ${permission.exileOthers} other"
        is CastingPermission.AlternativeCost -> "alternative cost ${permission.cost.render()}"
        is CastingPermission.Plot -> "plot (no mana cost)"
    }

/** The zone a cast draws from (CR 601.2a). */
private fun sourceName(source: CastSource): String =
    when (source) {
        CastSource.HAND -> "hand"
        CastSource.GRAVEYARD -> "graveyard"
        CastSource.EXILE -> "exile"
    }

/** The zone an activated ability functions from (CR 113.6). */
private fun scopeName(scope: AbilityZoneScope): String =
    when (scope) {
        AbilityZoneScope.Battlefield -> "battlefield"
        AbilityZoneScope.Hand -> "hand"
    }
