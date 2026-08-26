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
     * A "search your library for a matching card, put it somewhere, then shuffle" clause (CR 701.18),
     * or `null` for a definition with none. Ash Barrens' basic landcycling, the Landscapes' sacrifice
     * ability, Crop Rotation. Additive, flagged core (`P-SEARCH`).
     *
     * **The fifth clause, and it was the fourth-and-a-half all along.** This was a field of
     * [ActivatedAbility] alone, with a KDoc arguing it was "not one of the four" because it searches a
     * *whole* library and shuffles. That distinguished its contents, not its shape: it needs a
     * mid-resolution enumerated decision that a [ResolutionEffect] cannot make (ADR-004), which is the
     * only property [ResolutionClauses] is about. Keeping it off the carrier had two costs — a *spell*
     * that searches (Crop Rotation, Land Grant) was inexpressible, and the ability path ran the search
     * **instead of** the ordinary effect rather than after it. Both are fixed by moving it here.
     */
    val librarySearch: LibrarySearch? get() = null

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

    /**
     * A bare optional "you may draw N cards" clause (CR 601.3b), or `null` for a definition with none.
     * Ninja of the Deep Hours' *"you may draw a card"*. Additive, flagged core (`FW-OPTDRAW`).
     *
     * A clause rather than a [ResolutionEffect] for the reason every member here is one: the yes/no is a
     * decision, and ADR-004 forbids a callback out of an effect. See [OptionalDraw] for why it is a
     * separate clause from [optionalCostThenDraw] and [OptionalDiscardDraw] rather than either of them
     * with an empty cost.
     */
    val optionalDraw: OptionalDraw? get() = null

    /**
     * An **untargeted** "choose up to N permanents matching this, then untap them / return them to
     * their owners' hands" clause (CR 609.4), or `null` for a definition with none. Snap's "Untap up to
     * two lands" and Azorius Chancery's "return a land you control to its owner's hand". Additive,
     * flagged core (`FW-TAPUNTAP`).
     *
     * The first clause whose decision is over the **battlefield** rather than over cards in a library,
     * a hand, or a graveyard — and the first whose option list is entirely public (CR 400.2), so unlike
     * [libraryLook] and [eachOpponentDiscards] it hides nothing from anybody. See [PermanentSelection]
     * for why the permanents are chosen here, mid-resolution, rather than as targets.
     */
    val permanentSelection: PermanentSelection? get() = null

    /**
     * A "you may tap **or** untap [the target]" clause (CR 701.20a, CR 701.21a), or `null` for a
     * definition with none. Sewer-veillance Cam's enters-or-leaves trigger. Additive, flagged core
     * (`W8-G`).
     *
     * The first clause whose decision is neither a selection nor a yes/no but a **three-way** answer —
     * decline, tap, untap — and the first that operates on the resolving object's *target* rather than on
     * a set it derives for itself. [OptionalTapOrUntap] records why it is a clause and not a
     * [ModalSpell] mode, which is the diagnosis this member was written to correct.
     */
    val optionalTapOrUntap: OptionalTapOrUntap? get() = null

    /**
     * An optional "you may pay [OptionalManaThenDraw.cost]; if you do, draw N" clause (CR 601.3b), or
     * `null` for a definition with none. Nihil Spellbomb's dies trigger. Additive, flagged core
     * (`W8-D`).
     *
     * The first clause whose payment is **mana** rather than an object the player already holds — see
     * [OptionalManaThenDraw] for why that makes it a clause of its own rather than an
     * [OptionalCostMode] of [optionalCostThenDraw].
     */
    val optionalManaThenDraw: OptionalManaThenDraw? get() = null

    /**
     * A "target player exiles a card from their graveyard" clause (CR 701.3a), or `null` for a
     * definition with none. Relic of Progenitus'. Additive, flagged core (`W8-D`).
     *
     * The **second** clause whose decider is not the resolving object's controller, after
     * [eachOpponentDiscards], and the first whose decider is named by one of the object's own targets —
     * so unlike that one it may perfectly well be the controller, when the ability is pointed at its own
     * side. See [TargetPlayerExilesFromGraveyard].
     */
    val targetPlayerExilesFromGraveyard: TargetPlayerExilesFromGraveyard? get() = null

    /**
     * A "choose a card type, reveal the top N, put all of that type into your hand and the rest into
     * your graveyard" clause (CR 701.16, CR 609.4), or `null` for a definition with none. Winding Way's.
     * Additive, flagged core (`W8-D`).
     *
     * The sibling of [libraryReveal] and deliberately not a mode of it: the type is chosen **as the
     * spell resolves** rather than as it is cast, and the keep is *mandatory* rather than "up to". See
     * [ChosenTypeReveal] for both arguments in full.
     */
    val chosenTypeReveal: ChosenTypeReveal? get() = null

    /**
     * A "choose a colour, then do something with it" clause (CR 609.4), or `null` for a definition with
     * none. Prismatic Strands' "the color of your choice". Additive, flagged core (`FW-PREVENT2`).
     *
     * The first clause whose decision is over neither a zone nor the battlefield but over a **closed
     * vocabulary**: its five options are the five colours (CR 105.1) and are the same five on every
     * board, so unlike every sibling here it can never have an empty option list and never needs a
     * "can this be done at all" pre-check. See [ChosenColorEffect] for why it is distinct from
     * [CardDefinition.choosesColorAsItEnters], the CR 614.12 flag that looks like it and is a different
     * rule at a different moment.
     */
    val chosenColorEffect: ChosenColorEffect? get() = null

    /**
     * The condition this definition's clause is **conditional on** (CR 608.2c), or `null` — the default
     * — for a definition whose clause runs whenever the object resolves. Additive, flagged core
     * (`W9-D`). Torch the Tower's *"you scry 1"*, which happens only on the bargained branch.
     *
     * **Not a clause**, which is why it is declared here and excluded from [declaredClauses]: it opens no
     * decision, needs no pause, and orchestrates nothing. It is a gate on whichever clause the definition
     * *does* declare, so a definition may carry it alongside its one clause without tripping
     * [requireAtMostOneClause]. On a definition with no clause it is inert, and saying that here is what
     * stops a later reader wiring it to the ordinary [SpellDefinition.resolution] effect, which is a pure
     * function that can test any condition it likes for itself.
     *
     * See [ClauseCondition] for why an unconditional clause was the wrong shape for a conditional rider
     * and why this is not an [InterveningIf].
     */
    val clauseCondition: ClauseCondition? get() = null
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
            librarySearch,
            handRevealChoice,
            eachOpponentDiscards,
            optionalDraw,
            permanentSelection,
            optionalTapOrUntap,
            optionalManaThenDraw,
            targetPlayerExilesFromGraveyard,
            chosenTypeReveal,
            chosenColorEffect,
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
