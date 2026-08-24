package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.SourceClassKey

/**
 * Activates the mana ability of the first usable member of [sourceClass] in battlefield order
 * (CR 605.3): it pays the ability's cost — **tapping** the source, or **sacrificing** it for a
 * sacrifice-cost mana ability ([SourceClassKey.viaSacrifice] — an Eldrazi Spawn) — resolves the ability
 * immediately, no stack, no priority round (CR 605.3a–b), and adds the [produced] mana to
 * [player]'s pool. Emits [GameEvent.ManaAbilityActivated], then the cost's event ([GameEvent.ObjectTapped]
 * for `{T}`, [GameEvent.PermanentSacrificed] for a sacrifice), then one [GameEvent.ManaAdded] per mana.
 *
 * Which member is used is rules-irrelevant by construction — class members are payment-equivalent
 * (docs/design/mana-payment.md) — so the deterministic first-in-battlefield pick keeps replay exact
 * (ADR-006) without surfacing a meaningless choice.
 *
 * Triggered mana abilities that trigger off this activation (Utopia Sprawl, CR 605.1b) resolve here,
 * immediately after the intrinsic ability adds its mana and before control returns to payment — extra
 * mana joins the pool without touching the plan shape.
 *
 * **Planner/executor correspondence under a CR 605.2 count** (docs/design/mana-payment.md §8.3).
 * A mana ability's amount is read when it *resolves*, so an ability that adds "{C}{C}{C} if you
 * control the other two Urza lands" is re-evaluated here rather than taken from the plan. The
 * member search is what makes that safe: [sourceClassKeyOf] re-derives the whole key — profile
 * included, and the state-derived count is *inside* the profile — against the state as it now
 * stands, and only a member whose re-derived key equals the planned [sourceClass] is activated. So
 * an activation whose count moved between enumeration and payment finds no member and fails loudly
 * below, rather than quietly adding a different amount of mana than the plan promised. The
 * correspondence is therefore structural, not a convention this function has to remember.
 */
internal fun resolveTapForMana(
    state: GameState,
    player: PlayerId,
    sourceClass: SourceClassKey,
    produced: List<ManaType>,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index =
        battlefield.indexOfFirst { obj ->
            obj.owner == player &&
                // The same usability predicate and the same class derivation the plan's membership
                // was built from — including the CR 302.6 summoning-sickness gate and the CR 605.2
                // production count — so planner and executor pick from the identical member set
                // (docs/design/mana-payment.md §2.1, §8.3).
                manaSourceUsable(state, obj) &&
                sourceClassKeyOf(state, obj) == sourceClass
        }
    require(index >= 0) {
        "CR 601.2g: no usable member of source class $sourceClass remains for $player; " +
            "the plan was enumerated against this state, so either a member left the battlefield or a " +
            "CR 605.2 production count changed mid-payment — an engine defect either way"
    }
    require(produced in sourceClass.profile) { "CR 605: source class $sourceClass cannot produce $produced" }
    val source = battlefield[index]
    // The triggered-mana bonus is read before the source leaves the battlefield (a sacrifice removes it).
    val bonus = triggeredManaBonus(state, source.id)
    val activated =
        if (sourceClass.viaSacrifice) {
            // CR 605.1a: the mana ability's cost sacrifices the source; no {T}, and it may be tapped.
            sacrificePermanents(
                state.emit(GameEvent.ManaAbilityActivated(player, source.id, source.card)),
                player,
                listOf(source.id),
            )
        } else {
            state
                .copy(
                    sharedZones =
                        state.sharedZones.copy(
                            battlefield = battlefield.removingAt(index).addingAt(index, source.copy(tapped = true)),
                        ),
                ).emit(GameEvent.ManaAbilityActivated(player, source.id, source.card))
                .emit(GameEvent.ObjectTapped(source.id, source.card))
        }
    // CR 605.1a: the ability adds every mana of the chosen production alternative — one mana for an
    // ordinary source, three for an Urza's Tower with Tron assembled.
    val withPrimary = produced.fold(activated) { current, mana -> addManaToPool(current, player, mana) }
    // CR 605.1b, CR 605.3: triggered mana abilities of Auras on this source (Utopia Sprawl) resolve
    // now — no stack, no priority — adding their mana to the same controller's pool. Whatever the
    // payment does not consume floats until the step ends (CR 500.4).
    return bonus.fold(withPrimary) { current, bonusMana -> addManaToPool(current, player, bonusMana) }
}
