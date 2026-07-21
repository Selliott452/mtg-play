package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.SourceClassKey

/**
 * Activates the tap-for-mana ability of the first untapped member of [sourceClass] in
 * battlefield order (CR 605.3): taps it, resolves the ability immediately — no stack, no
 * priority round, unlike every other activated ability (CR 605.3a–b) — and adds the chosen
 * [produced] mana to [player]'s pool. Emits [GameEvent.ManaAbilityActivated], then
 * [GameEvent.ObjectTapped] for the `{T}` cost (CR 605.1a), then [GameEvent.ManaAdded].
 *
 * Which member taps is rules-irrelevant by construction — class members are
 * payment-equivalent (docs/design/mana-payment.md) — so the deterministic first-in-battlefield
 * pick keeps replay exact (ADR-006) without surfacing a meaningless choice.
 *
 * Phase 5 hook: triggered mana abilities that trigger off this activation (Utopia Sprawl,
 * CR 605.1b) resolve here, immediately after the intrinsic ability adds its mana and before
 * control returns to payment — extra mana joins the pool without touching the plan shape.
 */
internal fun resolveTapForMana(
    state: GameState,
    player: PlayerId,
    sourceClass: SourceClassKey,
    produced: ManaType,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index =
        battlefield.indexOfFirst { obj ->
            obj.owner == player &&
                !obj.tapped &&
                productionProfile(state, obj)?.let { SourceClassKey(obj.card, it) } == sourceClass
        }
    require(index >= 0) {
        "CR 601.2g: no untapped member of source class $sourceClass remains for $player; " +
            "the plan was enumerated against this state, so this is an engine defect"
    }
    require(produced in sourceClass.profile) { "CR 605: source class $sourceClass cannot produce $produced" }
    val source = battlefield[index]
    val tapped =
        state
            .copy(
                sharedZones =
                    state.sharedZones.copy(
                        battlefield = battlefield.removingAt(index).addingAt(index, source.copy(tapped = true)),
                    ),
            ).emit(GameEvent.ManaAbilityActivated(player, source.id, source.card))
            .emit(GameEvent.ObjectTapped(source.id, source.card))
    return addManaToPool(tapped, player, produced)
}
