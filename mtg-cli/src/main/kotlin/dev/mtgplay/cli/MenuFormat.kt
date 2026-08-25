package dev.mtgplay.cli

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color

/*
 * Shared menu formatting: one-based numbered option lines, the input hints, and small combat/colour
 * labels. Menu position `k` always maps to engine option index `k - 1` (documented once here), which
 * is what makes the parser's conversion uniform across every request kind.
 */

/** Renders [labels] as one-based numbered lines: `  1) ...`, `  2) ...`, ... (position `k` = index `k-1`). */
internal fun numbered(labels: List<String>): List<String> =
    labels.mapIndexed { index, label -> "  ${index + 1}) $label" }

/** The input hint for a single pick (one number). */
internal const val SINGLE_HINT: String = "  Enter one number."

/** The input hint for a fixed-size selection of [count] items. */
internal fun sizedHint(count: Int): String =
    "  Enter exactly $count number(s), comma-separated (e.g. 1,2). [Enter] = the first $count."

/**
 * The input hint for a ranged selection of between [minimum] and [maximum] items (CR 601.2c). Spelled
 * out rather than folded into [sizedHint] because the two say opposite things about declining: a sized
 * cost must be paid in full, while an "up to N" target line may legitimately be answered with none.
 */
internal fun rangedHint(
    minimum: Int,
    maximum: Int,
): String =
    if (minimum == 0) {
        "  Enter up to $maximum number(s), comma-separated (e.g. 1,2). [Enter] = none."
    } else {
        "  Enter $minimum to $maximum number(s), comma-separated (e.g. 1,2). [Enter] = the first $minimum."
    }

/** The input hint for an any-size subset selection (attackers, blockers). */
internal const val SUBSET_HINT: String = "  Enter numbers comma-separated, or [Enter] for none."

/** The input hint for a full ordering (a permutation of every option). */
internal const val ORDER_HINT: String = "  Enter all numbers in the order you want, comma-separated (e.g. 2,1,3)."

/**
 * A combatant's label for a combat menu (CR 508/509): its full battlefield label - name, effective
 * P/T, and keywords - or the printed name if it has somehow left the battlefield.
 */
internal fun combatantLabel(
    view: MatchView,
    id: ObjectId,
    card: CardRef,
): String {
    val obj =
        view.state.sharedZones.battlefield
            .firstOrNull { it.id == id }
    return if (obj != null) permanentLabel(view.state, obj) else card.name
}

/** A colour's name (CR 105.1), e.g. `green`. */
internal fun colorName(color: Color): String = color.name.lowercase()
