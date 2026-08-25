package dev.mtgplay.rules

import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject

/**
 * The per-seat view of an open "target opponent reveals their hand and you choose a card from it" pause
 * (CR 701.16a) — Duress's and Mesmeric Fiend's. Additive (`FW-HIDDENCHOICE`,
 * docs/design/exile-and-return.md §7).
 *
 * **Carried in full to every seat, and that is the rules-correct answer rather than a convenience.** The
 * sibling of [PendingRevealView] and the deliberate opposite of [PendingLibraryLookView]: CR 701.16a's
 * reveal makes the cards known to *all* players, so redacting them here would be modelling a game in
 * which Duress reveals a hand privately, which is not the game the card describes. An opponent who
 * watches a Duress resolve learns the whole hand, and the seat view is the only place that can be told
 * so (ADR-007 hides what is hidden; it does not hide what a card publishes).
 *
 * This is therefore the one pending view in the engine that makes a **hidden zone temporarily public**,
 * and it is why `ViewLeakPropertySpec`'s hidden-name oracle has to know about it: the property "an
 * opponent's hand names never appear in your view" is false while a reveal is open, by rule, and the
 * spec is extended to say so rather than relaxed to stop checking.
 *
 * @property decider the resolving object's controller, who chooses (the printed "*you* choose").
 * @property revealer the targeted opponent whose hand is revealed.
 * @property revealed every card in [revealer]'s hand, in hand order — the whole hand, not only the
 *   choosable subset, because CR 701.16a reveals all of it.
 * @property outcome what will happen to the chosen card (CR 701.7a discard or CR 701.3a exile).
 * @property sourceCard the resolving object's printed identity.
 */
data class PendingHandRevealView(
    val decider: PlayerId,
    val revealer: PlayerId,
    val revealed: List<GameObject>,
    val outcome: RevealedCardOutcome,
    val sourceCard: CardRef,
)
