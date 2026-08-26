package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Playing a card **from exile at its printed cost** (CR 118.5, CR 601.2a, CR 715.3d) — the permissions
 * that are granted by a *rule or another object* rather than declared on the card being played, and so
 * are read off a marker on the exiled object instead of off
 * [dev.mtgplay.core.definition.SpellDefinition.castingPermissions].
 *
 * Two of them exist and they differ only in which marker admits a card and how long it lasts: Reckless
 * Impulse's *"until the end of your next turn, you may play those cards"* (CR 118.5), which expires and
 * therefore records a turn; and CR 715.3d's *"for as long as that card remains exiled, that player may
 * play it"*, which does not expire and therefore records a boolean. Neither replaces a cost, and both
 * say **play** rather than *cast*, so both reach a land as well as a spell.
 *
 * Split out of `ActionEnumeration.kt` by `W10-B` rather than suppressed when the second one tripped that
 * file's function budget. Both scans stay `internal` and both are still called from the one enumeration
 * (ADR-005: what `legalPriorityOptions` returns is exactly what the engine will accept), so the split is
 * a file boundary and not a second source of legality truth.
 */

/**
 * The priority-window options for the exile cards [seat] has been *granted permission to play*
 * (CR 118.5, CR 601.2a) — Reckless Impulse's two. One option per playable card: a normal-cost
 * [PriorityOption.CastSpell] from [CastSource.EXILE] for a spell, or a [PriorityOption.PlayLand] from
 * exile for a land. Additive (`W8-D`).
 *
 * **Not [permissionCastOptions] with another zone.** That function reads permissions the *card declares
 * about itself*; this permission was granted by a different object's resolution to whatever happened to
 * be on top of a library, so it is read off the exiled object's
 * [dev.mtgplay.core.state.GameObject.playGrantedTurn] marker instead — and it grants no alternative
 * cost, so the cast is priced at the printed cost exactly as a cast from hand is.
 *
 * The marker is checked live against the turn ([playGrantMarkerAllows]) as well as being cleared at
 * cleanup, so an expired permission can never be enumerated even for one window.
 */
internal fun grantedExilePlayOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption> =
    objectsInZone(state, seat, CastSource.EXILE)
        .filter { playGrantMarkerAllows(state, it) }
        .mapNotNull { obj -> exilePlayOption(state, seat, obj) }

/**
 * The priority-window options for the exile cards [seat] sent **on an adventure** (CR 715.3d) — Fang
 * Dragon, exiled by its own *Forktail Sweep* resolving. One option per marked card, played at its
 * printed cost. Additive (`W10-B`).
 *
 * **Not [permissionCastOptions] with another zone, and not a [dev.mtgplay.core.definition.CastingPermission]
 * at all** — for [grantedExilePlayOptions]' reason twice over. The permission is granted by CR 715.3d
 * rather than declared on the card, it costs the **printed** cost (nothing replaces it), and CR 715.3d
 * is explicit that *"it can't be cast as an Adventure this way"*: the option is the card's normal half,
 * so it is enumerated through [exilePlayOption]'s ordinary printed-cost path, which knows nothing about
 * faces and therefore cannot offer one.
 *
 * The marker is read off the exiled object ([dev.mtgplay.core.state.GameObject.onAnAdventure]) and has
 * no expiry to check: CR 715.3d's grant lasts *"for as long as that card remains exiled"*, and the
 * marker leaves with the object the moment the card does (CR 400.7).
 */
internal fun adventureExilePlayOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption> =
    objectsInZone(state, seat, CastSource.EXILE)
        .filter { it.onAnAdventure }
        .mapNotNull { obj -> exilePlayOption(state, seat, obj) }

/**
 * The one playable option for the exile card [obj] at its **printed** cost (CR 118.5, CR 601.2a,
 * CR 715.3d), or `null` when it is not playable from this window — the shared body of the two exile
 * scans above, which differ only in which marker admits a card to it.
 *
 * A spell is a normal-cost [PriorityOption.CastSpell] from [CastSource.EXILE]; a **land** is the
 * play-land special action from exile, because both printed grants say *play* rather than *cast* and
 * dropping the land half would silently delete every land the grant reaches.
 */
private fun exilePlayOption(
    state: GameState,
    seat: PlayerId,
    obj: GameObject,
): PriorityOption? {
    val definition = state.definitions[obj.card]
    return when {
        definition is SpellDefinition && castIsLegal(state, seat, definition, obj.id, CastSource.EXILE) ->
            PriorityOption.CastSpell(obj.id, obj.card, CastSource.EXILE)
        definition.isLand() && playLandIsLegal(state, seat) ->
            PriorityOption.PlayLand(obj.id, obj.card, CastSource.EXILE)
        else -> null
    }
}

/**
 * Whether the exile object [obj] still carries a live "you may play this" permission (CR 118.5) — it has
 * a [dev.mtgplay.core.state.GameObject.playGrantedTurn] marker at all, and the turn it was granted on
 * has not yet been followed by a completed turn of its owner's.
 *
 * The second half is belt-and-braces against the CR 514.2 cleanup that clears the marker: they agree by
 * construction, and stating the condition here as well means an expired permission is never enumerated
 * even in a state a test constructed by hand.
 */
internal fun playGrantMarkerAllows(
    state: GameState,
    obj: GameObject,
): Boolean {
    val granted = obj.playGrantedTurn ?: return false
    return !playGrantHasExpired(state, obj.owner, granted)
}

/**
 * Whether a play permission granted on turn [grantedTurn] to [owner] has run out by the *end* of the
 * turn now in progress (CR 118.5) — "until the end of your next turn": true exactly when the current
 * turn is [owner]'s and is strictly later than [grantedTurn].
 *
 * The one derivation of the duration, shared by enumeration and by the CR 514.2 cleanup that clears the
 * marker, so the two cannot disagree about when the permission ends — the discipline `FW-OPTCOST` used
 * for the intervening-if's two checks, and load-bearing for the same reason: a permission enumerated
 * after it expired is an illegal action the engine offered (ADR-005).
 */
internal fun playGrantHasExpired(
    state: GameState,
    owner: PlayerId,
    grantedTurn: Int,
): Boolean = state.turn.activePlayer == owner && state.turn.number > grantedTurn
