package dev.mtgplay.core.definition

/**
 * A "**you may** exile a card matching [restriction] from your graveyard. **If you do**, &lt;effect&gt;"
 * clause (CR 404, CR 608.2c) — Masked Vandal's *"When this creature enters, you may exile a creature
 * card from your graveyard. If you do, exile target artifact or enchantment an opponent controls."*
 * Additive, flagged core (`W9-F`).
 *
 * **It is a clause because of ADR-004, and it carries [thenEffect] because of ordering.** The
 * mid-resolution choice — which graveyard card, or none — is a decision, and a [ResolutionEffect] may
 * not make one. Every other member of [ResolutionClauses] runs *after* the object's ordinary effect,
 * which is exactly wrong here: the printed line makes the effect **conditional on the choice**, so an
 * effect declared in the ordinary slot would already have exiled the targeted permanent before anybody
 * was asked whether to pay for it. Hanging the gated half on the clause is what keeps the two in
 * printed order; the carrying object declares a no-op ordinary effect.
 *
 * **Not [TriggeredAbility.optional].** That flag is the CR 603.3 "you may" that wraps a *whole* ability
 * and is answered as a bare yes/no before it resolves. This one is answered by naming a specific object
 * out of a zone, and the answer is what the "if you do" reads — a yes/no could not say *which* creature
 * card left the graveyard, which matters to anything that later counts or returns it.
 *
 * **Not a cost, either.** Nothing here is announced or paid at CR 601.2b/601.2h: the exile happens
 * during CR 608.2 resolution, long after the ability was put on the stack. The practical consequence is
 * the one Masked Vandal's rulings turn on — the *target* was chosen back at CR 603.3d, so an ability
 * whose target has since become illegal does not resolve at all (CR 608.2b) and **no card is exiled from
 * the graveyard**, because the question is never asked.
 *
 * **An empty selection is not offered as a decision.** With no matching card in the deciding player's
 * graveyard the "you may" has no yes branch, so the clause performs nothing and asks nothing rather than
 * surfacing a request whose only answer is "no" (ADR-005).
 *
 * **Core/rules split (ADR-009.)** This declares the shape; `mtg-rules` owns enumerating the matching
 * graveyard cards (a public zone, CR 400.2 — no ADR-007 filtering), surfacing the decline index beside
 * them, performing the CR 400.7 exile, and running [thenEffect] only on the branch that exiled.
 *
 * @property restriction which of the deciding player's graveyard cards may be exiled (CR 404) —
 *   [GraveyardCardRestriction.CREATURE] for Masked Vandal.
 * @property thenEffect the "if you do" half, performed only when a card was actually exiled. It receives
 *   the same [ResolutionContext] the ordinary effect would have, targets included, so a gated effect may
 *   read the object's CR 603.3d targets exactly as an ungated one does.
 */
data class OptionalGraveyardExileGate(
    val restriction: GraveyardCardRestriction,
    val thenEffect: ResolutionEffect,
)
