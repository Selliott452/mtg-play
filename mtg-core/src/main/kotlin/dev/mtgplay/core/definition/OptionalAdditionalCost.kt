package dev.mtgplay.core.definition

/**
 * An **optional additional cost with a chosen object** (CR 601.2b) — "you may [do this] as you cast
 * this spell", where the doing consumes something the caster picks. Additive, flagged core
 * (`FW-BARGAIN`). Troublemaker Ouphe's bargain.
 *
 * **The third corner of a square the engine had three of.** Lay the cost kinds out on two axes:
 *
 * | | mandatory | optional |
 * |---|---|---|
 * | **mana** | the printed cost (CR 202) | [SpellDefinition.kicker] (CR 702.33a) |
 * | **non-mana, chosen object** | [AdditionalCost] (CR 601.2b) | *this* |
 *
 * Each cell needs its own declaration because each needs a different *pipeline*. A mandatory non-mana
 * cost needs a selection and no announcement — Grab the Prize discards whether you like it or not, and
 * the spell is uncastable when it cannot. Kicker needs an announcement and no selection — there is
 * nothing to pick, only a price to accept. This cell needs **both, in order**: a yes/no whose "yes"
 * then opens a selection, and whose "no" is always legal. Folding it into [AdditionalCost] with an
 * `optional` flag would make the mandatory case's payability gate (a spell that cannot pay is not
 * enumerated) silently wrong for the optional one, where the spell is castable either way and it is
 * the *announcement* that must be withheld.
 *
 * **Declining is its own enumerated index, never an absence** (ADR-005). The engine surfaces the yes/no
 * whenever the cost *could* be paid, and settles it to "no" without a request only when it could not —
 * so an agent that can bargain is always shown that it can, and one that cannot is never offered a
 * "yes" that dead-ends. That is [SpellDefinition.kicker]'s rule, applied to a cost that consumes a
 * permanent rather than mana.
 *
 * **Whether it was paid is linked information** (CR 702.166b for bargain), recorded on the cast record
 * as [dev.mtgplay.core.state.StackEntry.Spell.optionalCostPaid] and, for a permanent, carried onto the
 * entering object as [dev.mtgplay.core.state.GameObject.optionalCostPaidWhenCast] — the bridge CR 400.7
 * otherwise breaks. [InterveningIf.SourcePaidOptionalAdditionalCost] is what reads it back.
 *
 * Sealed so the pipeline handles every shape exhaustively. A card declares **at most one** (the field
 * is a single nullable), which is what lets one recorded boolean answer "was it bargained?" without
 * ambiguity.
 */
sealed interface OptionalAdditionalCost {
    /**
     * **Bargain** (CR 702.166a): "You may sacrifice an artifact, enchantment, or token as you cast this
     * spell." Troublemaker Ouphe.
     *
     * A `data object` because the keyword is parameterless — CR 702.166a spells the cost out in full and
     * there is nothing for a card to vary. The three things it may consume are the keyword's, not a
     * filter the card chooses, which is why this carries no [SacrificeFilter]: encoding it as one would
     * invite a second bargain card to declare a different set, and there is no such card because there
     * is no such keyword.
     *
     * **"Or token" is the axis no existing filter has**, and it is not a card type — a token is a
     * *non-card game object* (CR 111.1), so a 1/1 Warrior token qualifies while a Warrior *creature
     * card* on the battlefield does not qualify by being a Warrior. The engine tests it the way it
     * tests tokenhood everywhere else, `definitions[card] is TokenDefinition`, rather than by widening
     * [SacrificeFilter] with a pseudo-type. See `BargainCost.kt` for the union.
     *
     * Note the union is genuinely a union and not a narrowing: an artifact **token** matches twice and
     * is offered once, and a creature that is neither artifact nor enchantment nor token — the ordinary
     * case — is not offered at all.
     */
    data object Bargain : OptionalAdditionalCost
}
