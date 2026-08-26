package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.zone.ZoneId

/**
 * The two P6.2a marker-scope invariants, in their own file so the [InvariantChecker] file stays within
 * its function budget:
 * - [Invariant.PLOT_MARKER_SCOPE]: the plotted-turn marker (CR 702.140) is exile-only;
 * - [Invariant.CHOSEN_COLOUR_SCOPE]: the as-enters chosen colour (CR 614.12) is battlefield-only;
 * - [Invariant.KICKED_MARKER_SCOPE]: the was-kicked marker (CR 702.33f) is battlefield-only.
 *
 * Both operate on the residence list so corrupt placements are directly testable, mirroring the madness
 * marker's exile-only scope and tapped's battlefield-only scope.
 */
internal fun checkP62aMarkerScopes(residences: List<ZoneResidence>): List<Violation> =
    buildList {
        residences
            .filter { it.obj.plottedTurn != null && it.zone != ZoneId.Exile }
            .forEach {
                add(
                    Violation(
                        Invariant.PLOT_MARKER_SCOPE,
                        "CR 702.140: object ${it.obj.id.value} is plotted-marked in ${it.zone}, but the marker is " +
                            "an exile-only status",
                    ),
                )
            }
        residences
            .filter { it.obj.kickedWhenCast && it.zone != ZoneId.Battlefield }
            .forEach {
                add(
                    Violation(
                        Invariant.KICKED_MARKER_SCOPE,
                        "CR 702.33f: object ${it.obj.id.value} is kicked-marked in ${it.zone}, but it is a " +
                            "battlefield-only status — the fresh object born of any zone move carries none",
                    ),
                )
            }
        residences
            .filter { it.obj.chosenColor != null && it.zone != ZoneId.Battlefield }
            .forEach {
                add(
                    Violation(
                        Invariant.CHOSEN_COLOUR_SCOPE,
                        "CR 614.12: object ${it.obj.id.value} carries a chosen colour in ${it.zone}, but it is a " +
                            "battlefield-only status",
                    ),
                )
            }
    }

/**
 * [Invariant.ENTERED_TURN_SCOPE] (`W9-A`): the entry-turn stamp (CR 603.6a) is battlefield-only and
 * never names a turn that has not happened.
 *
 * Its own function rather than a fourth filter in [checkP62aMarkerScopes] because it is the only marker
 * scope here that also needs the *current* turn to check, and because that file's function is at its
 * budget. Both halves matter: a stamp off the battlefield is a status the CR 400.7 rebirth should have
 * dropped, and a stamp in the future is an engine defect that would make "entered this turn" answer yes
 * forever.
 */
internal fun checkEnteredTurnScope(
    residences: List<ZoneResidence>,
    currentTurn: Int,
): List<Violation> =
    buildList {
        residences
            .filter { it.obj.enteredTurn != null && it.zone != ZoneId.Battlefield }
            .forEach {
                add(
                    Violation(
                        Invariant.ENTERED_TURN_SCOPE,
                        "CR 603.6a: object ${it.obj.id.value} carries an entry turn in ${it.zone}, but it is a " +
                            "battlefield-only quantity — the fresh object born of any zone move carries none",
                    ),
                )
            }
        residences
            .mapNotNull { residence -> residence.obj.enteredTurn?.let { residence to it } }
            .filter { (_, entered) -> entered > currentTurn }
            .forEach { (residence, entered) ->
                add(
                    Violation(
                        Invariant.ENTERED_TURN_SCOPE,
                        "CR 603.6a: object ${residence.obj.id.value} claims to have entered on turn $entered, " +
                            "but the game is on turn $currentTurn",
                    ),
                )
            }
    }
