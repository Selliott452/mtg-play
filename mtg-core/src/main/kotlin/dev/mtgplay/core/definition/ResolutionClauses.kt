package dev.mtgplay.core.definition

/**
 * The **post-resolution clauses** a resolving stack object may carry (CR 608.2c): the parts of an
 * effect that `mtg-rules` orchestrates around a mid-resolution pause rather than running as a pure
 * [ResolutionEffect]. Card-definition data, additive and flagged core (`FW-CLAUSEHOOK`).
 *
 * **Why this is an interface and not four fields on [SpellDefinition].** Every clause here was
 * originally declared on [SpellDefinition] alone, so the hook was *spell-shaped*: an ability that
 * resolved could not carry any of them. That killed every card whose enters-the-battlefield trigger
 * looks at cards or whose activated ability draws — Faerie Seer's "When this creature enters, scry 2"
 * is the same CR 701.17a clause as Preordain's, hanging off CR 603 instead of CR 601. Lifting the four
 * properties onto a carrier implemented by [SpellDefinition], [TriggeredAbility], and
 * [ActivatedAbility] alike means the orchestration is written **once** and the three resolution paths
 * differ only in how the resolving object leaves the stack (CR 608.2m for a spell, CR 113.7a for an
 * ability). It is the generalisation docs/design/library-look.md §13 item 6 flagged.
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares *which* clause a
 * definition carries; `mtg-rules` owns running it — taking the pool, surfacing the enumerated decision
 * (ADR-005), applying the answer, and completing the resolution.
 *
 * **At most one clause per definition.** No MVP card prints two, the orchestration order between two
 * clauses would be a rule this type does not state, and running them in field-declaration order would
 * be exactly the silent approximation CONVENTIONS.md forbids. [requireAtMostOneClause] is the loud
 * gate, called from each implementor's `init` where it has one.
 */
interface ResolutionClauses {
    /**
     * A "reveal top N, put up to M into hand, rest into graveyard" clause (CR 701.16), or `null` for a
     * definition with none. Malevolent Rumble's and Kruphix's Insight's reveal. Public: the revealed
     * cards emit [dev.mtgplay.core.event.GameEvent.CardsRevealed] and every seat sees them.
     */
    val libraryReveal: LibraryReveal? get() = null

    /**
     * A private "look at these cards, then arrange them between the top of your library, the bottom of
     * your library, and your hand" clause (CR 701.14, CR 701.17), or `null` for a definition with none.
     * Preordain's scry 2, Impulse's one-to-hand, Brainstorm's two-from-hand-on-top — and, now that the
     * hook is carrier-shaped, Faerie Seer's and Sea Gate Oracle's enters-the-battlefield looks.
     *
     * The sibling of [libraryReveal], never a mode of it: a look is private (CR 701.14a) where a reveal
     * is public (CR 701.16a), and its whole decision is an *ordering*.
     */
    val libraryLook: LibraryLook? get() = null

    /**
     * An optional "you may [discard a card | sacrifice a land]; if you do, draw N" clause (CR 601.3b),
     * or `null` for a definition with none. Highway Robbery's clause: a mode choice, then that mode's
     * cost-object selection, then the draw.
     */
    val optionalCostThenDraw: OptionalCostThenDraw? get() = null

    /**
     * A mandatory "draw N cards, then discard M cards" clause (CR 601.2c), or `null` for a definition
     * with none. Faithless Looting's. The discard routes through the CR 614/616 framework, so a
     * discarded madness card is exiled instead.
     */
    val drawThenDiscard: DrawThenDiscard? get() = null

    /**
     * A "target opponent reveals their hand, you choose a card from it, discard or exile it" clause
     * (CR 701.16), or `null` for a definition with none. Duress's and Mesmeric Fiend's. The decider is
     * the resolving object's **controller** — see [HandRevealChoice], whose KDoc records why the
     * printed "*you* choose" makes this a public-information choice rather than a non-controller one.
     */
    val handRevealChoice: HandRevealChoice? get() = null

    /**
     * An "each opponent discards a card" clause (CR 701.7a), or `null` for a definition with none.
     * Refurbished Familiar's. The **only** clause whose decider is not the resolving object's
     * controller, and the only one whose option list is hidden from that controller — see
     * [EachOpponentDiscards].
     */
    val eachOpponentDiscards: EachOpponentDiscards? get() = null
}

/**
 * The clauses [ResolutionClauses] actually declares, in declaration order — empty for a definition
 * that carries none. The one place the properties are enumerated together, so a further clause is
 * added here rather than at every site that asks "does this carry a clause?".
 */
val ResolutionClauses.declaredClauses: List<Any>
    get() =
        listOfNotNull(
            libraryReveal,
            libraryLook,
            optionalCostThenDraw,
            drawThenDiscard,
            handRevealChoice,
            eachOpponentDiscards,
        )

/**
 * Fails loudly if [clauses] declares more than one post-resolution clause (see [ResolutionClauses]).
 * Called from an implementor's `init`; `mtg-rules` re-checks nothing, because a definition that got
 * past this gate cannot present the ambiguity.
 */
fun requireAtMostOneClause(
    clauses: ResolutionClauses,
    describe: () -> String,
) {
    val declared = clauses.declaredClauses
    require(declared.size <= 1) {
        "CR 608.2c: ${describe()} declares ${declared.size} post-resolution clauses, but the engine " +
            "orchestrates at most one — the order between two is a rule no card in the pool states"
    }
}
