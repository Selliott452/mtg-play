package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype

/**
 * A description of the battlefield permanents a card's text picks out — "an Urza's Power-Plant you
 * control", "each Elf on the battlefield", "each creature you control with defender" (CR 109.4,
 * CR 205.2, CR 205.3). The declaration half of a board-state count; `mtg-rules` owns deciding which
 * objects match it (ADR-009).
 *
 * Declarative data rather than a predicate lambda, for the same three reasons
 * [PermanentRestriction] is an enum: a card definition is data (ADR-003); the value takes part in
 * structural equality inside `SourceClassKey`, which is what lets two identically-conditioned mana
 * sources collapse into one payment class (docs/design/mana-payment.md §2); and it is
 * serialisable onto the wire and renderable in the CLI.
 *
 * Still deliberately narrow, and still not the general `ObjectPredicate` that cost-modification.md
 * §10 (C1) plans — power, zone and negation remain *absent* rather than stubbed. `FW-COUNTERS` widened
 * it by exactly the two axes Overgrown Battlement's "each creature you control with **defender**"
 * needs, in the way this KDoc predicted one would be found: a property added and the rules-side
 * matcher broken until it reads it.
 *
 * Every axis is independently optional and they conjoin; **at least one must be present**, because a
 * filter that constrains nothing would silently count the whole battlefield.
 *
 * @property subtype the subtype every matching permanent must have (CR 205.3) — `Subtype("Elf")`,
 *   `Subtype("Urza's Tower")` — or `null` to constrain no subtype. Note the Urza land types are
 *   printed **hyphenated**: the type line reads `Land — Urza's Power-Plant`, not `Urza's Power
 *   Plant`, which is the card's *name*. Getting that wrong is silent — the count would simply always
 *   be zero.
 * @property controlledByYou whether the permanent must be controlled by the object whose text this
 *   is (Urza's Mine's "**you control**"), or may be controlled by anyone (Priest of Titania's
 *   "each Elf **on the battlefield**").
 * @property cardType the card type every matching permanent must have (CR 205.2) — the "**creature**"
 *   in "each creature you control with defender" — or `null` to constrain no card type. Additive
 *   (`FW-COUNTERS`). Read printed, like every other card-type read in the engine, because no
 *   type-changing effect exists in the pool (that is `FW-TYPECHANGE`).
 * @property keyword the keyword ability every matching permanent must have (CR 702) — the
 *   "**with defender**" — or `null` to constrain no keyword. Additive (`FW-COUNTERS`). Unlike
 *   [subtype] and [cardType] this is read **layered**, through the CR 613 effective-keyword accessor:
 *   a keyword is the one axis here that effects already grant and remove (layer 6, CR 613.1f), so
 *   reading it printed would be wrong today, not merely wrong later.
 */
data class PermanentFilter(
    val subtype: Subtype? = null,
    val controlledByYou: Boolean,
    val cardType: CardType? = null,
    val keyword: Keyword? = null,
) {
    init {
        require(subtype != null || cardType != null || keyword != null) {
            "CR 109.4: a permanent filter must constrain something; a filter with no subtype, no " +
                "card type and no keyword would match every permanent on the battlefield"
        }
    }
}
