package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameObject
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * **Attack requirements** (CR 508.1d) — the first rule in this engine that makes the declare-attackers
 * declaration something other than a free subset of the eligible creatures. Added by `W11` for goad
 * (CR 701.38a), the Undercity's Arena.
 *
 * Its own file, and the sibling of BlockerMinimums.kt one decision earlier, for the same reason that
 * one is its own file: it is a legality that no *option* can express. Every clause in
 * [eligibleAttackers] narrows **which** creatures may be declared and so lives in the option list;
 * a requirement says a listed option **must** be taken, which no list of independently-legal options
 * can say. So it is published on the request for the deciding seat to see (ADR-005) and enforced
 * across the chosen set in DecisionValidation.kt — deriver and validator reading the same published
 * requirements, so the enumeration a seat is shown and the legality its answer is checked against
 * cannot drift apart.
 *
 * **What CR 508.1d actually demands, and what this implements.** The rule is a maximisation: the
 * declaration must obey as many requirements as possible without violating any *restriction*. This
 * engine has no attack restrictions at all — every printed bar on attacking in the gauntlet pool
 * (defender, tapped, summoning sickness) is an **eligibility** rule that removes the creature from
 * [eligibleAttackers] entirely, so it is never an option to begin with. With no restrictions in play
 * the maximisation collapses to "every requirement is satisfiable, so satisfy every requirement",
 * which is what [attackRequirementsFor] returns and what the validator enforces. The first printed
 * attack *restriction* is where this function grows a real CR 508.1d search; until then a search would
 * be untested machinery around a constant answer.
 */

/**
 * The creatures this declaration is **required** to include (CR 508.1d), in the same order as
 * [eligible] — empty for every combat in which nothing is goaded, which is every combat in a game
 * where no Undercity Arena has resolved.
 *
 * [eligible] is [eligibleAttackers]' answer, and taking it rather than recomputing is what makes the
 * "if able" of CR 701.38a exact: a goaded creature that is tapped, summoning sick, or has gained
 * defender is not able to attack, is therefore absent from [eligible], and generates no requirement.
 * The rule needs no separate ability test because "able to attack" and "is an eligible attacker" are
 * the same predicate, computed once.
 *
 * Only the goading half that bites is turned into a requirement. CR 701.38a's *"attacks a player
 * other than you if able"* names a defending player, and in a two-player game the declaration has
 * exactly one to name — so the half is either already satisfied (the goaded creature's controller is
 * not the goader, and their only opponent *is* the goader… which the "if able" then waives) or
 * satisfied by the only option there is. It constrains no two-player declaration in either direction,
 * which is why it is recorded on the permanent ([GameObject.goadedBy]) and reported on the request
 * rather than enforced: the day a third seat exists, the fact is already there.
 */
internal fun attackRequirementsFor(eligible: List<GameObject>): List<DecisionRequest.DeclareAttackers.Required> =
    eligible.mapNotNull { attacker ->
        attacker.goadedBy?.let { goader ->
            DecisionRequest.DeclareAttackers.Required(
                attacker = attacker.id,
                card = attacker.card,
                goadedBy = goader,
            )
        }
    }
