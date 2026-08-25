package dev.mtgplay.core.definition

/**
 * Which objects a [StaticContinuousEffect] applies to (CR 611.2c) — the "affected set" a static
 * ability's continuous effect modifies. Additive, flagged core (P4.1).
 *
 * Sealed so `mtg-rules` resolves the set exhaustively and a new selector breaks compilation
 * rather than being silently ignored. The MVP pool exercised exactly one member, [Enchanted]; the
 * keyword-tail packet takes the first of the two extension points this KDoc reserved by adding
 * [Self]. Computed-set selectors ("other creatures you control", "Humans you control") remain
 * unbuilt — they are a second, genuinely harder problem, because CR 611.2c locks a *resolution*-
 * generated set at creation while a *static* one is re-evaluated continuously
 * (docs/design/layer-system.md §2, docs/design/duration.md §9.5).
 */
sealed interface AffectedSet {
    /**
     * The one object the generating Aura is attached to (CR 303.4, CR 611.2c) — the affected set
     * of every MVP continuous effect. Empty when the Aura is attached to nothing, in which case
     * the Aura is falling off as a state-based action (CR 704.5m) and its effect is inactive.
     */
    data object Enchanted : AffectedSet

    /**
     * The generating permanent itself (CR 611.2c) — a static ability whose continuous effect modifies
     * only its own source: Goblin Tomb Raider's "as long as you control an artifact, **this creature**
     * gets +1/+0 and has haste". Additive, flagged core (`FW-CONDSTATIC`).
     *
     * Never empty while the source is on the battlefield, which is what makes it simpler than
     * [Enchanted]: an Aura's affected set is a second object that may have gone, whereas a permanent
     * always affects itself. The effect-collection walk is over battlefield permanents, so the source
     * is present by construction and the set is a single id rather than a nullable one.
     */
    data object Self : AffectedSet
}
