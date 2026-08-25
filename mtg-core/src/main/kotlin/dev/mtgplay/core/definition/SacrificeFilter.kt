package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

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
 * **This is the one filter every sacrifice cost is written against**, and it was not always. It began
 * carrying card types alone, beside a [SacrificeRequirement] that carried a single printed **subtype**
 * alone, and the two were kept apart on the argument that widening either would disturb the other's
 * callers. `W8-D` found the card that makes the split untenable: Dread Return's flashback cost is
 * "Sacrifice three creatures", a *permission*-side cost naming a **card type**, which the subtype-only
 * requirement could not say at all. The fix was to give this type the [subtype] axis its own KDoc
 * already named as an extension point and to have [SacrificeRequirement] carry one of these, so
 * "which permanents may pay a sacrifice cost?" has exactly one answer wherever the cost is attached —
 * a card's own additional cost, an activated ability's cost, or a casting permission's.
 *
 * It is still **not** [PermanentFilter], the board-count filter behind Urza-land and
 * Priest-of-Titania mana: that one takes part in `SourceClassKey` structural equality, so widening it
 * would reshape payment-class collapsing (docs/design/mana-payment.md §2) for a reason unrelated to
 * mana.
 *
 * Declarative data rather than a predicate lambda, for the reasons [PermanentFilter] and
 * [PermanentRestriction] are: a card definition is data (ADR-003), the value takes part in structural
 * equality, and it is serialisable onto the wire and renderable in the CLI.
 *
 * Control is ownership in the MVP pool, as everywhere else. "Sacrifice **another**" is *absent* rather
 * than stubbed: no card in the pool prints it (see [AbilityCost.Sacrifice]), and a false `another` flag
 * would silently under-enumerate. A count restriction and a power/toughness axis remain the extension
 * points; adding one is a property here plus a broken matcher in `mtg-rules`, which is the intended way
 * a gap is found.
 *
 * @property anyOfCardTypes the card types a permanent may have to match (CR 300.1); a permanent matches
 *   when it has **at least one** of them. `{LAND}` is "sacrifice a land"; `{ARTIFACT}` is "sacrifice an
 *   artifact"; `{ARTIFACT, CREATURE}` is "sacrifice an artifact or creature"; `{CREATURE}` is Dread
 *   Return's "three creatures". Empty means the filter says nothing about card types, which is legal
 *   only alongside a [subtype].
 * @property subtype a printed subtype every matching permanent must have (CR 205.3) — Fireblast's
 *   "two **Mountains**", Lava Dart's flashback "a **Mountain**" — or `null` for a filter that names no
 *   subtype. Read through
 *   [dev.mtgplay.core.card.PrintedCharacteristics.hasSubtype], so a changeling is correctly **not**
 *   matched by a *land* subtype: CR 702.73a grants creature types, and Mountain is a land type.
 *
 * The two axes are **conjunctive**: a permanent matches when it has one of [anyOfCardTypes] (or the set
 * is empty) *and* has [subtype] (or it is `null`). No pool card prints both at once; stating the
 * conjunction here is what stops the rules-side matcher from having to invent a combining rule.
 */
data class SacrificeFilter(
    val anyOfCardTypes: PersistentSet<CardType> = persistentSetOf(),
    val subtype: Subtype? = null,
) {
    init {
        require(anyOfCardTypes.isNotEmpty() || subtype != null) {
            "CR 601.2h: a sacrifice cost names at least one card type or subtype its permanent may have"
        }
    }
}
