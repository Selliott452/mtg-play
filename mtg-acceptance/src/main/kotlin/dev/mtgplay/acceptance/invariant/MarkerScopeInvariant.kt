package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.zone.ZoneId

/**
 * The two P6.2a marker-scope invariants, in their own file so the [InvariantChecker] file stays within
 * its function budget:
 * - [Invariant.PLOT_MARKER_SCOPE]: the plotted-turn marker (CR 702.140) is exile-only;
 * - [Invariant.CHOSEN_COLOUR_SCOPE]: the as-enters chosen colour (CR 614.12) is battlefield-only.
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
