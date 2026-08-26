package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.PriorityOption

/** The number of lands a player may normally play in a turn (CR 305.2). */
internal const val LAND_PLAYS_PER_TURN: Int = 1

/**
 * Enumerates the legal options for [seat], who holds priority in [state] (ADR-005) — the
 * single source of legality truth: what this returns is exactly what the engine will accept,
 * and nothing else is representable.
 *
 * [PriorityOption.Pass] is always present (CR 117.3d). A [PriorityOption.CastSpell] is
 * enumerated for each hand card that clears every gate (so CR 601.2 will succeed and choosing
 * the option never dead-ends):
 * - it has a castable definition — inert cards (no definition at all) are simply absent
 *   (architect decision, P2.1);
 * - its timing class permits casting from this window (CR 117.1a, [timingPermitsWindow]);
 * - each of its required targets has at least one legal choice (CR 601.2c);
 * - at least one payment plan exists for its cost (CR 601.2g, docs/design/mana-payment.md).
 *
 * A [PriorityOption.PlayLand] is enumerated for each land card in hand (CR 115.2a) exactly
 * when the CR 116.2a special action is legal ([playLandIsLegal]). Land-ness is read from the
 * card's definition, so an undefined land stays inert like any other undefined card.
 *
 * A **cast-from-elsewhere** option (docs/decklists.md) is enumerated for each castable permission that
 * is legal right now (ADR-005 both directions): a flashback or escape spell in the graveyard whose
 * cost, additional cost, timing, and targets all check out appears; the same card in hand casts
 * normally, a distinct option. A **hand alternative cost** (Fireblast's "sacrifice two Mountains
 * rather than pay this spell's mana cost", CR 118.9) is likewise a distinct hand option, offered
 * beside the normal cast. Madness is *not* here — it is offered only as its reflexive trigger resolves
 * (CR 702.35b). Plot's cast-from-exile (CR 702.140) slots into the exile scan when its permission
 * exists; no MVP mainboard card plots, so the exile scan currently yields nothing.
 *
 * A **granted play permission** (CR 118.5) — Reckless Impulse's "until the end of your next turn, you
 * may play those cards" — is enumerated from exile per marked object ([grantedExilePlayOptions]), and so
 * is an **adventurer card waiting in exile** (CR 715.3d, [adventureExilePlayOptions]). Neither is a
 * [dev.mtgplay.core.definition.CastingPermission]: both are granted by a rule or another object's
 * resolution rather than declared by the card being played, both cost the printed cost, and — because
 * both say *play* rather than *cast* — both reach a land as well as a spell. See
 * `ExilePlayPermissions.kt`.
 *
 * A **face cast** (CR 715.3, CR 720.3) *is* a permission, and a synthesized one: a card declaring an
 * alternative face has an [dev.mtgplay.core.definition.CastingPermission.Adventure] or
 * [dev.mtgplay.core.definition.CastingPermission.Omen] offered beside its normal hand cast, gated
 * against the **face's** definition (CR 715.3a). See `CardFaces.kt`.
 *
 * Option order is fixed for deterministic indices (ADR-006): the pass, then hand normal casts and land
 * plays in hand order, then graveyard, exile, and hand permission casts in that order, then the granted
 * exile plays, then the adventure exile plays.
 */
internal fun legalPriorityOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption> =
    buildList {
        add(PriorityOption.Pass)
        state.player(seat).hand.forEach { obj ->
            val definition = state.definitions[obj.card]
            when {
                definition is SpellDefinition && castIsLegal(state, seat, definition, obj.id, CastSource.HAND) ->
                    add(PriorityOption.CastSpell(obj.id, obj.card))
                definition.isLand() && playLandIsLegal(state, seat) ->
                    add(PriorityOption.PlayLand(obj.id, obj.card))
                else -> Unit
            }
        }
        addAll(permissionCastOptions(state, seat, CastSource.GRAVEYARD))
        addAll(permissionCastOptions(state, seat, CastSource.EXILE))
        addAll(permissionCastOptions(state, seat, CastSource.HAND))
        // CR 118.5: cards an effect granted permission to *play* from exile (Reckless Impulse) — a
        // normal-cost cast or, for a land, the play-land special action.
        addAll(grantedExilePlayOptions(state, seat))
        // CR 715.3d: a card exiled by its own Adventure resolving, which its controller may play from
        // exile for as long as it stays there — the card's **normal** half, never the Adventure again.
        addAll(adventureExilePlayOptions(state, seat))
        // CR 702.140: the plot special action, offered like a land play (sorcery timing).
        addAll(plotOptions(state, seat))
        // CR 602.1: activated abilities of the seat's permanents and (landcycling) hand cards.
        addAll(activationOptions(state, seat))
        // CR 702.49a: ninjutsu, an activated ability of a card in hand whose cost returns an unblocked
        // attacker. Synthesized rather than declared, so it is not part of activationOptions.
        addAll(ninjutsuOptions(state, seat))
    }

/**
 * The priority-window cast options for [seat]'s permissions whose source is [source] (CR 601.2): one
 * [PriorityOption.CastSpell] per card-permission pair that is legal to cast right now (ADR-005). Reads
 * only permissions [dev.mtgplay.core.definition.CastingPermission.offeredAtPriority] — so a madness
 * card waiting in exile is never offered here (it casts via its reflexive trigger).
 */
private fun permissionCastOptions(
    state: GameState,
    seat: PlayerId,
    source: CastSource,
): List<PriorityOption.CastSpell> =
    objectsInZone(state, seat, source).flatMap { obj ->
        val definition = state.definitions[obj.card] as? SpellDefinition ?: return@flatMap emptyList()
        // CR 715.3 / CR 720.3: the list includes the permission the engine synthesizes from a declared
        // alternative face, so an Adventure or an Omen is offered by exactly the machinery flashback and
        // escape go through — and gated against the **face's** definition, which is what CR 715.3a's
        // "only the alternative characteristics are evaluated to see if it can be cast" means (`W10-B`).
        castingPermissionsOf(definition)
            .filter { permission ->
                permission.source == source &&
                    permission.offeredAtPriority &&
                    permissionCastIsLegal(
                        state,
                        seat,
                        castDefinitionOf(state, obj.card, permission),
                        permission,
                        obj,
                    )
            }.map { PriorityOption.CastSpell(obj.id, obj.card, source, it) }
    }

/**
 * Whether this definition describes a land **card** (CR 305.1); `false` for an undefined card.
 *
 * **Printed types, and that is CR 613's scope rather than a deferral.** `FW-TYPECHANGE` rerouted every
 * *battlefield* card-type read through the layer engine and deliberately left this one alone: all four
 * of its callers ask about a card in a **hidden or non-battlefield zone** — the play-land enumeration
 * over a hand ([playLandIsLegal]'s companion arms), the same over a card in exile with a play grant,
 * Land Grant's "if you have no land cards in hand" in `CastConditions.kt`, and the play-land
 * execution's own re-check. CR 613 continuous effects apply to permanents on the battlefield; a card in a hand has
 * its printed type line and nothing else, so a layered read here would have nothing to add and no
 * battlefield object to compute from.
 *
 * It is recorded because the packet's own brief listed this file among the reads to reroute, and it was
 * wrong to: rerouting it would have replaced a correct printed read with a loud failure on every land
 * in hand.
 */
internal fun CardDefinition?.isLand(): Boolean = this != null && CardType.LAND in characteristics.cardTypes

/**
 * Whether [seat] may take the play-land special action right now (CR 116.2a): they are the
 * active player, in a main phase of their own turn, with the stack empty (CR 305.1), and the
 * turn's land drop is still available (CR 305.2 — one land per turn). Priority is the caller's
 * concern: options are only ever enumerated for the priority holder (CR 117.1).
 */
internal fun playLandIsLegal(
    state: GameState,
    seat: PlayerId,
): Boolean =
    seat == state.turn.activePlayer &&
        (state.turn.phase == TurnPhase.PRECOMBAT_MAIN || state.turn.phase == TurnPhase.POSTCOMBAT_MAIN) &&
        state.sharedZones.stack.isEmpty() &&
        state.turn.landsPlayedThisTurn < LAND_PLAYS_PER_TURN

/**
 * Whether every CR 601.2 gate passes for [seat] casting the hand card [castObjectId] defined by
 * [definition] normally: timing, targets, an affordable **modified** cost, and any intrinsic
 * additional discard (Grab the Prize) or sacrifice (Eviscerator's Insight) cost.
 *
 * The cost is priced by the shared [totalCost] (CR 601.2f, docs/design/cost-modification.md), not by
 * the printed cost: an affinity spell is legal to cast exactly when its *reduced* cost is payable,
 * and pricing it here against the printed cost would hide a legal option from the agent (ADR-005 in
 * the direction that silently shrinks the action space). The private `castableCost` this replaced was
 * one of four independent cost expressions that had to agree by coincidence; all four sites now call
 * [totalCost], so agreement is structural rather than a property somebody has to maintain.
 *
 * The sacrifice cost also narrows the payment enumeration ([minimalSacrificeReservation]): a permanent
 * that produces mana by being *sacrificed* cannot both fund the mana and satisfy the sacrifice, so a
 * plan spending it is not offered. Nothing else is reserved — tapping a land for mana and then
 * sacrificing it is legal (docs/design/mana-payment.md §2.2).
 */
internal fun castIsLegal(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId,
    source: CastSource,
): Boolean =
    timingPermitsWindow(state, seat, definition.timing) &&
        // CR 601.2b–c: a modal card is castable when *some* mode's targets exist, not when the card's
        // do — it has none of its own (`FW-MODAL`, SpellModes.kt). For a non-modal card this is
        // exactly `targetsAvailable`, so there is one question here rather than two.
        someModeIsCastable(state, definition, seat, Chooser.Spell(castObjectId)) &&
        // CR 601.2c for a card printing the word "target" more than once (`W9-C`, Searing Blaze): a
        // **search for a satisfying assignment**, not a per-line conjunction. Asking each line
        // independently says yes on a board whose only creature belongs to a seat the first line could
        // not name, offering a cast that dead-ends at an empty option list (ADR-005).
        //
        // Only for a non-modal card: a modal one has no lines until CR 601.2b settles its modes, and
        // `someModeIsCastable` above is that card's whole gate. `targetLinesOf` refuses to hold both
        // shapes at once, so this guard is the same mutual exclusion stated at the call site.
        (
            definition.modes.isNotEmpty() ||
                targetLinesSatisfiable(state, targetLinesOf(definition, emptyList()), seat, Chooser.Spell(castObjectId))
        ) &&

        additionalDiscardSatisfiable(state, seat, definition, castObjectId, source) &&
        additionalSacrificeSatisfiable(state, seat, definition) &&
        // CR 601.2b (`W9-D`): a non-consuming additional cost needs something to name; with an empty
        // board and an empty hand Monstrous Emergence is not castable at all.
        powerSourceCostSatisfiable(state, seat, definition, castObjectId, source) &&
        castCostIsPayable(state, seat, CastSubject(definition, permission = null, castObjectId = castObjectId))

/**
 * Whether [timing] permits [seat] to act from the current window (CR 117.1a): instant speed
 * whenever the player has priority; sorcery speed only for the active player, during a main
 * phase of their own turn, with the stack empty.
 *
 * Shared by casting a spell and — since `FW-MANACOST` — by activating an ability that prints
 * "Activate only as a sorcery" ([ActivatedAbility.timing]). CR 602.5d defines that restriction as
 * "the player must follow the timing rules for casting a sorcery spell, though the ability isn't
 * actually a sorcery", so it is deliberately the *same* predicate rather than a parallel one: the
 * rule says the two windows are identical, and one function is how they stay identical.
 */
internal fun timingPermitsWindow(
    state: GameState,
    seat: PlayerId,
    timing: TimingClass,
): Boolean =
    when (timing) {
        TimingClass.INSTANT_SPEED -> true
        TimingClass.SORCERY_SPEED ->
            seat == state.turn.activePlayer &&
                (state.turn.phase == TurnPhase.PRECOMBAT_MAIN || state.turn.phase == TurnPhase.POSTCOMBAT_MAIN) &&
                state.sharedZones.stack.isEmpty()
    }

/**
 * Whether every target [spec] requires can be chosen by caster or activator [seat] (CR 601.2c): a spell
 * or ability that cannot be fully targeted cannot legally be cast or activated, so it is excluded from
 * enumeration (ADR-005) rather than allowed to dead-end mid-pipeline. An Aura whose enchant restriction
 * matches no battlefield object (CR 303.4a) is uncastable, as is a removal spell whose
 * [TargetSpec.TargetPermanent] restriction matches nothing on the battlefield — Terminate is simply not
 * an option with no creature in play — and a counter with no legal spell on the stack, which is the
 * first spec whose answer changes several times inside one priority round.
 *
 * **The test is the spec's minimum, not "at least one"** (`FW-MULTITGT`), and the generalisation
 * replaced a `when` that asked every targeting member the same question. For an "exactly one" spec the
 * two are identical, which is why every card that predates this framework behaves unchanged. For
 * [dev.mtgplay.core.definition.TargetCount.UpTo] the minimum is zero, so Faerie Macabre's ability is
 * activatable with two empty graveyards and Blood Fountain's with an empty one — a card that says "up
 * to" may take none, and refusing to enumerate it would delete a legal play. For an "exactly N" spec
 * with N above one it is the CR 601.2c rule in full: "two target creatures" is not castable with one
 * creature on the battlefield.
 *
 * [chooser] is the object that would be doing the choosing — [Chooser.Spell] for a cast, and since
 * `P-ABILSOURCE` [Chooser.Ability] for an activation. Its exclusion never changes the answer at
 * *enumeration* time (the card is still in its source zone and so is not on the stack), but naming it
 * keeps every call site honest about the identity whose absence CR 601.2c's re-validation later depends
 * on — and its **source** half does change the answer, because a spell or ability whose only candidate
 * has protection from it (CR 702.16b) has no legal target and must not be enumerated at all
 * (docs/design/protection.md §6, row 3).
 */
internal fun targetsAvailable(
    state: GameState,
    spec: TargetSpec,
    seat: PlayerId,
    chooser: Chooser,
    context: TargetContext = TargetContext.NONE,
): Boolean = legalTargets(state, spec, seat, chooser, context).size >= spec.count.minimum
