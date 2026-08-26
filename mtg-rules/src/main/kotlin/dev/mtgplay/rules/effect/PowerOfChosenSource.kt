package dev.mtgplay.rules.effect

import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.powerOnBattlefieldOrLastKnown
import dev.mtgplay.rules.engine.printedPowerOf

/**
 * Effect primitive: the power a [ChosenPowerSource] supplies **as the reading object resolves**
 * (CR 608.2h) — the published building block a "damage equal to the power of the creature you chose or
 * the card you revealed" resolution composes (ADR-003; Monstrous Emergence is the first client). `W9-D`.
 *
 * **A primitive rather than a fold left to the card, because the two branches read two different rules
 * and a card cannot reach either.** A battlefield creature's power is the CR 613 layered value, computed
 * from the whole board by machinery that is `internal` to `mtg-rules`; a card in a hand has the printed
 * power CR 109.3 gives it and nothing else, and routing *that* through the layer system would fail
 * looking for a battlefield object that is not there. A card definition composing this by hand would have
 * to pick one rule for both branches, and either pick is a wrong card:
 *
 * - printed power for both would ignore every pump and every `-X/-0` — Cryoshatter cast in response to
 *   Monstrous Emergence really does shrink the damage, and that is the line the opponent plays for;
 * - layered power for both would throw the moment a hand card was revealed.
 *
 * **The chosen creature's answer is live, and last known only when it has to be** (CR 608.2h, CR 113.7a).
 * While the creature is on the battlefield this is its current power, recomputed here rather than
 * captured when the cost was paid — a pump between the cast and the resolution grows the damage. Once it
 * has left, there is nothing to compute and the value recorded at its departure is used
 * (`LastKnownPower.kt`), which is the CR's own answer rather than a fallback.
 *
 * **A negative power is returned as it is.** CR 208.3 lets a creature's power be negative and this
 * function does not clamp, because the clamping rule belongs to the *consumer*: CR 120.8 says damage of
 * zero or less is simply not dealt, which [dealDamage] already enforces, and a different consumer might
 * want the number itself.
 *
 * Fails loudly for a [ChosenPowerSource.ChosenCreature] the state has neither on the battlefield nor in
 * its last-known record: every such id was a battlefield creature when the cost was paid and every
 * departure records one, so the absence of both is an engine defect and not a rules case.
 */
fun powerOfChosenSource(
    state: GameState,
    source: ChosenPowerSource,
): Int =
    when (source) {
        is ChosenPowerSource.ChosenCreature ->
            powerOnBattlefieldOrLastKnown(state, source.objectId)
                ?: error(
                    "CR 608.2h: the chosen creature ${source.objectId} is neither on the battlefield nor " +
                        "in the last-known-power record; every departure records one",
                )
        // CR 109.3: a card outside the battlefield has only its printed characteristics.
        is ChosenPowerSource.RevealedCard -> printedPowerOf(state, source.card)
    }
