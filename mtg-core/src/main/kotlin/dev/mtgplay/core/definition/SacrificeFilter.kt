package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import kotlinx.collections.immutable.PersistentSet

/**
 * Which permanents a **sacrifice cost** may be paid with (CR 601.2h, CR 602.1, CR 701.17) — the noun
 * half of "sacrifice a land", "sacrifice an artifact", "sacrifice an artifact or creature". Additive,
 * flagged core (`FW-ADDSAC`). Card-definition *declaration*; `mtg-rules` owns deciding which battlefield
 * objects match it (ADR-009), whether enough of them exist for the cost to be payable, surfacing the
 * enumerated selection, and performing the sacrifice.
 *
 * Declarative data rather than a predicate lambda, for the reasons [PermanentFilter] and
 * [PermanentRestriction] are: a card definition is data (ADR-003), the value takes part in structural
 * equality, and it is serialisable onto the wire and renderable in the CLI.
 *
 * **Why this is a third filter type and not a reuse of one of the two that exist.** Both existing ones
 * are keyed on the wrong axis for a sacrifice cost:
 * - [SacrificeRequirement] — the *permission*-side sacrifice cost (Fireblast's two Mountains, Lava
 *   Dart's flashback Mountain) — is keyed on a single printed **subtype**, so it cannot say "artifact"
 *   at all, let alone "artifact or creature". Widening it would also change a frozen wire DTO that has
 *   nothing to do with this cost.
 * - [PermanentFilter] — the board-count filter behind Urza-land and Priest-of-Titania mana — carries a
 *   subtype and a controller axis and *deliberately* no card type; it takes part in `SourceClassKey`
 *   structural equality, so widening it would reshape payment-class collapsing
 *   (docs/design/mana-payment.md §2) for a reason unrelated to mana.
 *
 * Deliberately narrow, on the same principle: it carries exactly the one axis the pool's sacrifice
 * costs print — a set of card types, satisfied by any one of them — and nothing else. Control is
 * ownership in the MVP pool, as everywhere else. "Sacrifice **another**" is *absent* rather than
 * stubbed: no card in the pool prints it (see [AbilityCost.Sacrifice]), and a false `another` flag
 * would silently under-enumerate. A count restriction, a subtype axis, and a power/toughness axis are
 * the extension points; adding one is a property here plus a broken `when` in the rules-side matcher,
 * which is the intended way a gap is found.
 *
 * @property anyOfCardTypes the card types a permanent may have to match (CR 300.1); a permanent matches
 *   when it has **at least one** of them. `{LAND}` is "sacrifice a land"; `{ARTIFACT}` is "sacrifice an
 *   artifact"; `{ARTIFACT, CREATURE}` is "sacrifice an artifact or creature". Never empty — a cost that
 *   matched nothing would be unpayable by construction.
 */
data class SacrificeFilter(
    val anyOfCardTypes: PersistentSet<CardType>,
) {
    init {
        require(anyOfCardTypes.isNotEmpty()) {
            "CR 601.2h: a sacrifice cost names at least one card type its permanent may have"
        }
    }
}
