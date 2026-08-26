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
 * Whether the exile object [obj] still carries a live "you may play this" permission (CR 118.5) — that
 * is, whether it has a [dev.mtgplay.core.state.GameObject.playGrantedTurn] marker at all.
 *
 * **The marker's presence *is* the permission, and the CR 514.2 cleanup is its sole authority.**
 * [playGrantEndsAtThisCleanup] clears it at the end of the owner's next turn and nothing else touches
 * it, so a marker that survives into the window the engine is enumerating for is live by construction.
 *
 * This used to re-derive the duration here as well, "belt-and-braces" against a hand-built state, and
 * that second check was **wrong in the one window the card is bought for**. The cleanup asks *"does this
 * grant end at the end of the turn now finishing?"*; enumeration asks *"is this grant live right now?"*
 * Those are different questions, and the cleanup's answer is `true` for the whole of the owner's next
 * turn — so sharing the derivation denied Reckless Impulse's cards on exactly the turn its
 * "until the end of your next turn" exists to cover. It was not even a conservative failure: because the
 * cleanup's question is false on every turn that is not the owner's, the shared check flipped back to
 * *live* on each opponent turn thereafter.
 *
 * There is no correct parity-free way to re-derive "is it live" from a grant turn alone — the answer
 * depends on which turns between the grant and now belonged to the owner, which the state does not
 * record. A stale marker therefore means the cleanup did not run, which is a defect in the caller and
 * not something enumeration can paper over.
 */
internal fun playGrantMarkerAllows(
    @Suppress("UNUSED_PARAMETER") state: GameState,
    obj: GameObject,
): Boolean = obj.playGrantedTurn != null

/**
 * Whether a play permission granted on turn [grantedTurn] to [owner] **ends at the cleanup now
 * running** (CR 118.5, CR 514.2) — "until the end of your next turn" is over exactly when the turn
 * finishing is [owner]'s and is strictly later than [grantedTurn].
 *
 * **This is the cleanup's question and only the cleanup's.** It is asked at one moment — the end of a
 * turn — and is meaningless at any other, because it is `true` for the whole of the owner's next turn
 * and `false` again on every turn after that which is not theirs. Enumeration must not ask it; see
 * [playGrantMarkerAllows], which used to and denied the card on the one turn it was bought for.
 *
 * Named for the moment it answers rather than for a general "has expired", so the next caller cannot
 * make the same substitution by reading the name alone.
 */
internal fun playGrantEndsAtThisCleanup(
    state: GameState,
    owner: PlayerId,
    grantedTurn: Int,
): Boolean = state.turn.activePlayer == owner && state.turn.number > grantedTurn
