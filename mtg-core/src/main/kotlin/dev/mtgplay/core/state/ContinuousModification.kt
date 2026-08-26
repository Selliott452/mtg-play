package dev.mtgplay.core.state

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * *What* a resolution-generated continuous effect does to the object it affects (CR 611.2) — the
 * payload half of a [TimedContinuousEffect], separated from the *when* and *to what*. Additive,
 * flagged core (`FW-DURATION`, docs/design/duration.md §5.1).
 *
 * The counterpart of [dev.mtgplay.core.definition.StaticContinuousEffect]'s grant/modifier fields,
 * with the one difference that defines this framework: the modifiers are **plain integers, already
 * snapshotted** (CR 608.2h, CR 611.2d). A spell or ability that sets a value using a variable
 * calculates it once, on resolution, and the effect it creates keeps that value for its whole
 * duration — so "+X/+X where X is the number of Elves" is a number by the time it reaches here.
 * There is deliberately nowhere to put a [dev.mtgplay.core.definition.Magnitude.Dynamic], whose
 * live-recount semantics (CR 613.3c) are correct for a static Aura and silently wrong for a resolved
 * pump (docs/gauntlet-card-triage.md T16).
 *
 * @property grantedKeywords keyword abilities the affected object gains (CR 613.3 layer 6).
 * @property grantedEvasions block-legality restrictions the affected object gains (CR 613.3 layer 6,
 *   CR 509.1b) — Gingerbrute's "this creature can't be blocked this turn except by creatures with
 *   haste". A layer-6 ability grant like [grantedKeywords], and its own field for the same reason
 *   [dev.mtgplay.core.definition.StaticContinuousEffect.grantedProtections] is: the restriction is not
 *   a named CR 702 keyword and so cannot be a [Keyword] member.
 *
 *   The **static** counterpart deliberately has no such field. No card in the gauntlet grants an
 *   evasion through a permanent's static ability, and an always-empty field would be an untested
 *   branch of the layer-6 union — the same call
 *   [dev.mtgplay.core.definition.StaticContinuousEffect] makes in the other direction for mana
 *   abilities, which no *timed* effect grants (docs/design/duration.md §5.1).
 * @property powerMod the snapshotted layer-7c power modifier (CR 613.3 sublayer 7c); may be negative
 *   ("gets -2/-0") or zero.
 * @property toughnessMod the snapshotted layer-7c toughness modifier; may be negative or zero.
 * @property addedCardTypes card types the affected object **gains** in CR 613 layer 4 (CR 613.1d) —
 *   Kenku Artificer's "that artifact becomes a … artifact creature". Additive, flagged core
 *   (`FW-TYPECHANGE`).
 *
 *   **Gains, never replaces, and the distinction is CR 205.1b rather than a simplification.** When an
 *   effect says a permanent "becomes an artifact creature", the rule is that it *keeps* its existing
 *   card types and adds the named ones; only the wording "becomes a … in addition to its other types"
 *   versus an explicit removal ("is no longer a creature") tells the two apart, and no card in the
 *   gauntlet prints the removing form. A `removedCardTypes` field would therefore be an always-empty
 *   branch of the layer-4 application, which is the untested-branch shape [grantedEvasions]'s note
 *   argues against on the other side; it joins this type with the first card that removes a type.
 * @property addedSubtypes subtypes the affected object gains in layer 4 (CR 613.1d, CR 205.3) —
 *   Kenku Artificer's "Homunculus". Applied in the same layer as [addedCardTypes] because CR 613.1d
 *   is one layer for both; a separate field only because a card may add one without the other.
 * @property setPower the layer-**7b** power the affected object's power is *set* to (CR 613.4b), or
 *   `null` when the effect sets no power — Kenku Artificer's "becomes a 0/0". Additive, flagged core
 *   (`FW-SETPT`).
 *
 *   **Setting is a different sublayer from modifying, and the pair is what makes this card work.**
 *   CR 613.4b (7b, setting) is applied strictly before CR 613.4c (7c, modifying *and counters*), so an
 *   artifact set to 0/0 and simultaneously given three `+1/+1` counters is a 3/3 and not a dead 0/0 —
 *   the CR 704.5f ordering the card turns on. Writing "becomes a 0/0" as a 7c modifier of
 *   `-printedPower/-printedToughness` would give the same number here and a different one the moment
 *   any other effect touched the object, which is the plausible-looking approximation
 *   CONVENTIONS.md forbids.
 * @property setToughness the layer-7b toughness the object's toughness is set to (CR 613.4b), or
 *   `null`. Always set together with [setPower] by every card that prints the form ("becomes a 0/0"),
 *   but modelled as two independent nullable fields because CR 613.4b is stated per characteristic and
 *   a one-sided setter is a legal printing.
 */
data class ContinuousModification(
    val grantedKeywords: PersistentSet<Keyword> = persistentSetOf(),
    val powerMod: Int = 0,
    val toughnessMod: Int = 0,
    val grantedEvasions: PersistentSet<Evasion> = persistentSetOf(),
    val addedCardTypes: PersistentSet<CardType> = persistentSetOf(),
    val addedSubtypes: PersistentSet<Subtype> = persistentSetOf(),
    val setPower: Int? = null,
    val setToughness: Int? = null,
) {
    init {
        require(
            grantedKeywords.isNotEmpty() ||
                grantedEvasions.isNotEmpty() ||
                powerMod != 0 ||
                toughnessMod != 0 ||
                addedCardTypes.isNotEmpty() ||
                addedSubtypes.isNotEmpty() ||
                setPower != null ||
                setToughness != null,
        ) {
            "CR 613: a continuous effect that grants nothing, changes no type and modifies no power " +
                "or toughness classifies into no implemented layer; an unimplemented effect kind " +
                "must fail loudly, never be represented (docs/design/duration.md §5.1)"
        }
    }
}
