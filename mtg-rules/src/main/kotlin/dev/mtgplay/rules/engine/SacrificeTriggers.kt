package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger

/*
 * "Whenever you sacrifice another <subtype>" (CR 603.2, CR 701.17a) — the engine's first trigger that
 * watches a sacrifice, one concept and one file.
 *
 * **One detection site, because there is one sacrifice.** Every sacrifice in the engine — the CR 601.2h
 * cost side, the effect side, bargain, an "each opponent sacrifices" clause, a mana ability's
 * sacrifice-self — funnels through `sacrificeOnePermanent`, so this detector is called from exactly one
 * place and a new sacrifice path cannot forget it. That is the same "give it one home rather than be
 * careful six times" argument [announceBattlefieldDeparture] makes for CR 603.6c.
 *
 * **It runs before the permanent moves, and before the CR 614 death replacement gets a say.** Both halves
 * matter. The subtype question is a CR 613 layer-4 one and layers do not reach a graveyard, so "was it an
 * Eldrazi" is answerable only while the permanent is still on the battlefield (CR 603.6c, CR 603.10a: a
 * look-back-in-time trigger asks the pre-event state). And CR 701.17a's event is the *sacrifice*, not the
 * arrival in a graveyard, so a permanent whose death is replaced by an exile was still sacrificed and
 * still fires this.
 */

/**
 * Fires the [TriggerCondition.YouSacrificedAnother] abilities of [player]'s battlefield permanents for
 * the sacrifice of [sacrificedId] (CR 603.2, CR 701.17a), against the state as it was before the
 * permanent left.
 *
 * Three narrowings, all printed on the condition (see its KDoc):
 *
 * - **"you"** — only a source whose controller is the sacrificing [player] fires; control is ownership in
 *   the MVP pool, so the battlefield fold is filtered on `source.owner == player`;
 * - **"another"** — a source is never fired by its own sacrifice, so a Writhing Chrysalis fed to its own
 *   engine puts no counter on itself;
 * - **the subtype** — answered through [hasSubtype], the changeling- and layer-4-aware seam, rather than
 *   off [dev.mtgplay.core.card.PrintedCharacteristics.subtypes], so a permanent granted the type qualifies
 *   and one that lost it does not.
 *
 * The fired trigger carries the *source* as last-known information and no subject: "put a +1/+1 counter on
 * this creature" needs the source alone, and the sacrificed permanent is gone by the time the trigger
 * resolves (CR 603.10). A trigger whose source has itself left the battlefield before resolution is the
 * card's problem to guard, not this detector's — CR 603.10 fires it either way.
 */
internal fun detectSacrificeTriggers(
    state: GameState,
    player: PlayerId,
    sacrificedId: ObjectId,
): GameState =
    state.sharedZones.battlefield
        .filter { it.owner == player && it.id != sacrificedId }
        .fold(state) { current, source ->
            val abilities =
                battlefieldTriggersMatching(current, source.card) { condition ->
                    condition is TriggerCondition.YouSacrificedAnother &&
                        hasSubtype(current, sacrificedId, condition.subtype)
                }
            abilities.fold(current) { inner, ability ->
                enqueuePendingTrigger(
                    inner,
                    PendingTrigger(
                        sourceId = source.id,
                        sourceCard = source.card,
                        controller = source.owner,
                        ability = ability,
                    ),
                )
            }
        }
