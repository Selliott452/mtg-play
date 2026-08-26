package dev.mtgplay.acceptance.driver

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/**
 * The stock [Responder] policies the scripted driver uses by default.
 *
 * Auto-responders live in the harness, not the engine: the engine never auto-passes (ADR-004), so
 * convenience like "pass every window" is a driver's job.
 */
object Responders {
    /**
     * Passes every priority window; when forced to discard down to maximum hand size (CR 514.1),
     * discards the lowest-indexed cards; and in combat declares **no** attackers and **no**
     * blockers (a passive, do-nothing combat policy — the flagged combat behaviour of this
     * driver). The deterministic baseline policy for driving lands-only games to deck-out and for
     * [ScriptedGame.passUntil]. It never casts and never attacks or blocks, so the casting
     * requests (CR 601.2) and the blocker-ordering request (CR 509.2, reachable only after a
     * block is declared) are all unreachable for it — reaching one fails loudly instead of
     * guessing. The one exception is a target choice reached from **trigger placement** (CR 603.3d),
     * which a passive game genuinely can reach: that takes the first enumerated target.
     */
    val PASS_AND_DISCARD_LOWEST: Responder =
        Responder { request, state ->
            when (request) {
                is DecisionRequest.ChooseAction -> {
                    val passIndex = request.options.indexOfFirst { it is PriorityOption.Pass }
                    check(passIndex >= 0) {
                        "CR 117.3d: passing must always be enumerated, options were ${request.options}"
                    }
                    Decision.SingleSelect(request.id, passIndex)
                }
                is DecisionRequest.ChooseDiscards ->
                    Decision.MultiSelect(request.id, (0 until request.count).toList())
                // CR 508.1 / CR 509.1: the empty selection declares no attackers / no blockers.
                is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
                is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
                // CR 603.3b: a passive game still fires triggers (an aura falling off returns Rancor);
                // order them in enumeration order, the deterministic identity permutation.
                is DecisionRequest.OrderTriggers ->
                    Decision.MultiSelect(request.id, request.options.indices.toList())
                // CR 702.35b: a passive game may discard a madness card at cleanup; decline the reflexive cast.
                // The kicker announcement (CR 601.2b) shares this request kind and is likewise declined,
                // which is unreachable here because this policy never casts.
                is DecisionRequest.ChooseYesNo ->
                    Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
                // CR 601.2b: an X announcement only ever arises inside a cast, which this policy never
                // begins, so reaching one fails loudly rather than guessing a value.
                is DecisionRequest.ChooseXValue ->
                    error("the pass-everything responder never casts, but an X announcement surfaced: $request")
                // CR 603.3d: a passive game still fires triggers, and a targeted one must choose its
                // target as it is put on the stack — take the first enumerated target deterministically.
                // A targets request with no trigger placement open can only have come from a cast or an
                // activation, neither of which this policy takes, so that stays a loud failure.
                is DecisionRequest.ChooseTargets ->
                    if (state.pendingTriggerTargets != null) {
                        Decision.SingleSelect(request.id, 0)
                    } else {
                        error("the pass-everything responder never casts, but a targets request surfaced: $request")
                    }
                // CR 601.2c: the multi-target sibling of the above, reachable by the same route — a
                // passive game's own trigger with an "up to N" target line. Take every option the
                // request allows, deterministically: the identity prefix is distinct by construction.
                is DecisionRequest.RangedSelection ->
                    if (state.pendingTriggerTargets != null) {
                        Decision.MultiSelect(request.id, (0 until request.maximumCount).toList())
                    } else {
                        error("the pass-everything responder never casts, but a targets request surfaced: $request")
                    }
                // CR 601.2b: a mode is only ever chosen while casting, which this policy never does.
                is DecisionRequest.ChooseModes ->
                    error("the pass-everything responder never casts, but a mode request surfaced: $request")
                is DecisionRequest.ChoosePaymentPlan ->
                    error("the pass-everything responder never casts, but a payment request surfaced: $request")
                is DecisionRequest.OrderBlockers ->
                    error("the pass-everything responder never blocks, but a blocker-order request surfaced: $request")
                is DecisionRequest.AssignTrampleDamage ->
                    error("the pass-everything responder never attacks, but a trample request surfaced: $request")
                // ChooseDiscards is handled above; the other sized selections are cost/ability choices this
                // policy never reaches (it never casts or activates).
                is DecisionRequest.SizedSelection ->
                    error("the pass-everything responder never pays cost selections, but one surfaced: $request")
                // CR 601.2b/701.60a: collect evidence is a cast-time cost, and this policy never casts.
                is DecisionRequest.SummedSelection ->
                    error("the pass-everything responder never collects evidence, but one surfaced: $request")
                is DecisionRequest.ChooseReplacement ->
                    error("the pass-everything responder never orders replacements: $request")
                is DecisionRequest.ChooseColor ->
                    error("the pass-everything responder never casts colour-choosing permanents: $request")
                is DecisionRequest.ChooseFromRevealed ->
                    error("the pass-everything responder never resolves a reveal effect: $request")
                is DecisionRequest.ChooseCostMode ->
                    error("the pass-everything responder never resolves a cost-then-draw spell: $request")
                is DecisionRequest.ChooseFromLibrary ->
                    error("the pass-everything responder never activates a library search: $request")
                is DecisionRequest.ChooseLibraryArrangement ->
                    error("the pass-everything responder never resolves a library look: $request")
                // CR 118.3a: an unless-pay pause only exists while a counter this policy never cast is
                // resolving, so reaching one is a defect, not a decision.
                is DecisionRequest.ChooseCounterPayment ->
                    error("the pass-everything responder never casts a counter: $request")
                // CR 701.16a: a revealed-hand choice only exists while a Duress-shaped object this policy
                // never cast is resolving, so reaching one is a defect, not a decision.
                is DecisionRequest.ChooseRevealedHandCard ->
                    error("the pass-everything responder never casts a hand-reveal spell: $request")
                // CR 608.2c: a tap-or-untap clause only exists while an object this policy never put on
                // the stack is resolving, so reaching one is a defect, not a decision.
                is DecisionRequest.ChooseTapOrUntap ->
                    error("the pass-everything responder never resolves a tap-or-untap clause: $request")
                // CR 601.3b: the optional pay-then-draw pause belongs to a Nihil Spellbomb this policy
                // never played, so reaching one is a defect rather than a decision.
                is DecisionRequest.ChooseOptionalManaPayment ->
                    error("the pass-everything responder never plays a pay-then-draw permanent: $request")
                // CR 701.3a: the targeted player's graveyard exile belongs to a Relic of Progenitus
                // ability this policy never activates — and this seat may be the *opponent* of whoever
                // did, which is exactly why it is spelled out rather than left to a catch-all.
                is DecisionRequest.ChooseGraveyardCardToExile ->
                    error("the pass-everything responder never activates a graveyard-exile ability: $request")
                // CR 401.1: a library-position choice belongs to a Deem Inferior this policy never cast —
                // and this seat is the *owner* of the targeted permanent, not the caster, which is
                // exactly why it is spelled out rather than left to a catch-all.
                is DecisionRequest.ChooseLibraryPosition ->
                    error("the pass-everything responder never casts a library-placement spell: $request")
                // CR 701.40a: an explore's destination choice only exists while a Map token this policy
                // never activates is resolving.
                is DecisionRequest.ChooseExploreDestination ->
                    error("the pass-everything responder never activates an exploring ability: $request")
                // CR 609.4: a resolution-time card-type choice only exists while a Winding Way this
                // policy never cast is resolving.
                is DecisionRequest.ChooseRevealedCardType ->
                    error("the pass-everything responder never casts a type-choosing reveal spell: $request")
                // CR 309.4: a dungeon branch only exists while a venture this policy never started is
                // resolving — nothing takes the initiative without a card being cast.
                is DecisionRequest.ChooseDungeonRoom ->
                    error("the pass-everything responder never takes the initiative: $request")
                // CR 103.4/103.5: the passive policy keeps every hand at seven — so no bottoming ever
                // follows — but bottoms the lowest indices if a mulligan game is ever driven this way.
                is DecisionRequest.MulliganRequest -> keepAtSeven(request)
            }
        }

    // CR 103.4: keep the drawn hand; if somehow prompted to bottom, choose the lowest indices.
    private fun keepAtSeven(request: DecisionRequest.MulliganRequest): Decision =
        when (request) {
            is DecisionRequest.ChooseMulligan -> Decision.SingleSelect(request.id, DecisionRequest.ChooseMulligan.KEEP)
            is DecisionRequest.ChooseCardsToBottom -> Decision.MultiSelect(request.id, (0 until request.count).toList())
        }
}
