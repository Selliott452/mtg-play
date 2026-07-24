package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * The signature-mechanism census for the MVP matchup corpus (P6.3, deliverable 1). Each of the fifteen
 * mechanisms the packet requires a floor on is detected from a finished [ScriptedGame] — its derived event
 * log (the observability channel, ADR-006) plus its recorded (state, request)/decision pauses — never from
 * any engine internals. The corpus asserts each mechanism occurs at least once across the seeds; this file
 * is the card-specific analysis the [dev.mtgplay.acceptance.fuzz.FuzzHarness] keeps out of its own
 * card-agnostic charter (it runs here, in the harness' per-seed inspector, where the whole game is in hand).
 *
 * Detection strategy per mechanism is documented on each [Mechanism] entry. Where an event uniquely marks a
 * mechanism (flashback's leave-stack exile, escape's cost-exile, an aura fall-off) that event is the signal;
 * where only a *decision* distinguishes it (a plot free-cast option taken, a madness reflexive accepted) the
 * pause is aligned with the decision that answered it; where only a *request shape* reveals it (a targeting
 * window that excluded an opponent's hexproof creature, a trample-assignment window) the pause's request and
 * state are read directly.
 */

/** One signature mechanism of the Mono-Red-Madness-vs-GW-Bogles matchup the corpus floors on (P6.3). */
internal enum class Mechanism(
    val label: String,
) {
    /** A madness card was cast from exile: its reflexive trigger's yes/no (CR 702.35b) was accepted. */
    MADNESS_CAST("madness cast"),

    /** A flashback spell was cast: it was exiled as it left the stack (CR 702.34e), the flashback signature. */
    FLASHBACK_CAST("flashback cast"),

    /** A plotted card was free-cast from exile (CR 702.140): the taken cast option carried a Plot permission. */
    PLOT_CAST("plot cast"),

    /** An escape spell was cast: it exiled other graveyard cards to pay its cost (CR 702.139a). */
    ESCAPE_CAST("escape cast"),

    /** Ash Barrens basic-landcycled: its hand-scoped activated ability went on the stack (CR 701.18). */
    LANDCYCLING_SEARCH("landcycling search"),

    /** A Blood token's rummage ability was activated (CR 602) — the loot the Madness deck pitches into. */
    BLOOD_TOKEN_ACTIVATION("Blood token activation"),

    /** Malevolent Rumble revealed the top of a library (CR 701.16) — multiple cards at once (vs a single find). */
    LIBRARY_REVEAL("library reveal (Rumble)"),

    /** Utopia Sprawl's triggered mana ability fired (CR 605.1b): one tap produced more than one mana. */
    SPRAWL_TRIGGERED_MANA("triggered-mana bonus (Sprawl)"),

    /** Sneaky Snacker's third-draw trigger resolved (CR 603.2), returning it from the graveyard. */
    SNACKER_RETURN("Snacker graveyard return"),

    /** Guttersnipe's spell-cast trigger fired (CR 603.2e) when its controller cast an instant or sorcery. */
    GUTTERSNIPE_TRIGGER("Guttersnipe trigger"),

    /** A blocked trampling attacker's above-lethal excess was assigned to the defender (CR 702.19e). */
    TRAMPLE_ASSIGNMENT("trample assignment"),

    /** A targeting window excluded an opponent's hexproof creature from its options (CR 702.11e). */
    HEXPROOF_TARGETING("hexproof-constrained targeting"),

    /** An Aura fell off (CR 704.5m): its enchanted object left, and a state-based action put it in the graveyard. */
    AURA_FALL_OFF("aura fall-off"),

    /** A creature token died (CR 704.5f/g) — it entered a graveyard by death, then ceased to exist (CR 704.5d). */
    TOKEN_DEATH("token death"),

    /** A player took a London mulligan (CR 103.4), shuffling their hand back and redrawing. */
    MULLIGAN_TAKEN("mulligan taken"),
}

/** Every mechanism, in declaration order — used for reporting and the missing-floor check. */
internal val ALL_MECHANISMS: List<Mechanism> = Mechanism.values().toList()

/**
 * The set of signature mechanisms that occurred in the finished [game]. Pure over the game's recorded
 * event log and pauses; safe to call once per seed from the harness inspector.
 */
internal fun mechanismsIn(game: ScriptedGame): Set<Mechanism> =
    eventMechanisms(game.state.events) + pauseMechanisms(game)

private val ASH_BARRENS = CardRef("Ash Barrens")
private val BLOOD = CardRef("Blood")
private val SNEAKY_SNACKER = CardRef("Sneaky Snacker")
private val GUTTERSNIPE = CardRef("Guttersnipe")

/** The smallest reveal size that distinguishes Malevolent Rumble's top-four reveal from Ash Barrens' single find. */
private const val RUMBLE_REVEAL_MINIMUM = 2

/** The mana-count within one activation window that only Utopia Sprawl's triggered bonus can reach (CR 605.1b). */
private const val SPRAWL_BONUS_MANA_THRESHOLD = 2

/**
 * The event-log detectors: the mechanisms a single unique game event marks ([singleEventMechanism]) plus the
 * two that only an event *pattern* reveals — Utopia Sprawl's bonus ([sprawlBonusFired]) and a token death
 * ([tokenDied]).
 */
private fun eventMechanisms(events: List<GameEvent>): Set<Mechanism> {
    val found = events.mapNotNullTo(mutableSetOf(), ::singleEventMechanism)
    if (sprawlBonusFired(events)) found += Mechanism.SPRAWL_TRIGGERED_MANA
    if (tokenDied(events)) found += Mechanism.TOKEN_DEATH
    return found
}

/** The mechanism a single [event] uniquely marks, or `null` — the one-event-one-mechanism detectors. */
private fun singleEventMechanism(event: GameEvent): Mechanism? =
    when (event) {
        is GameEvent.SpellExiledInsteadOfGraveyard -> Mechanism.FLASHBACK_CAST
        is GameEvent.CardsExiledForCost -> Mechanism.ESCAPE_CAST
        is GameEvent.AuraFellOff -> Mechanism.AURA_FALL_OFF
        is GameEvent.MulliganTaken -> Mechanism.MULLIGAN_TAKEN
        is GameEvent.CardsRevealed -> if (event.cards.size >= RUMBLE_REVEAL_MINIMUM) Mechanism.LIBRARY_REVEAL else null
        is GameEvent.AbilityActivated -> abilityMechanism(event.sourceCard)
        is GameEvent.TriggeredAbilityResolved ->
            if (event.sourceCard == SNEAKY_SNACKER) Mechanism.SNACKER_RETURN else null
        is GameEvent.TriggeredAbilityPutOnStack ->
            if (event.sourceCard == GUTTERSNIPE) Mechanism.GUTTERSNIPE_TRIGGER else null
        else -> null
    }

/** The mechanism an activated ability of [sourceCard] marks (Ash Barrens' landcycling, a Blood token's loot). */
private fun abilityMechanism(sourceCard: CardRef): Mechanism? =
    when (sourceCard) {
        ASH_BARRENS -> Mechanism.LANDCYCLING_SEARCH
        BLOOD -> Mechanism.BLOOD_TOKEN_ACTIVATION
        else -> null
    }

/**
 * Whether Utopia Sprawl's triggered mana ability fired (CR 605.1b): within a single mana-ability activation
 * window (bounded by [GameEvent.ManaAbilityActivated]) more than one mana is added — no other MVP source, but
 * a Sprawl-enchanted land, produces two mana from one tap.
 */
private fun sprawlBonusFired(events: List<GameEvent>): Boolean {
    var manaAddedInWindow = 0
    for (event in events) {
        when (event) {
            is GameEvent.ManaAbilityActivated -> manaAddedInWindow = 0
            is GameEvent.ManaAdded -> {
                manaAddedInWindow += 1
                if (manaAddedInWindow >= SPRAWL_BONUS_MANA_THRESHOLD) return true
            }
            else -> {}
        }
    }
    return false
}

/**
 * Whether a creature token died (CR 704.5f/g): a token entered a graveyard as a [GameEvent.CreatureDied]'s new
 * object, then that same graveyard object ceased to exist as a token (CR 704.5d) — distinguishing a death from
 * a token sacrificed for mana, which leaves via [GameEvent.PermanentSacrificed], not [GameEvent.CreatureDied].
 */
private fun tokenDied(events: List<GameEvent>): Boolean {
    val diedGraveyardIds =
        events.filterIsInstance<GameEvent.CreatureDied>().mapTo(mutableSetOf()) { it.graveyardObjectId }
    return events.any { it is GameEvent.TokenCeasedToExist && it.objectId in diedGraveyardIds }
}

/**
 * The pause detectors: mechanisms revealed only by a decision request's shape (a trample or hexproof
 * targeting window) or by the option a decision selected (a plot free-cast, a madness reflexive accept).
 * The driver records one pause per suspension and the answering decisions in the same order, so
 * `pauses[i]` is answered by `decisions[i]` (a trailing unanswered pause exists only for a game halted at
 * the cap, and [List.getOrNull] yields null there).
 */
private fun pauseMechanisms(game: ScriptedGame): Set<Mechanism> {
    val found = mutableSetOf<Mechanism>()
    val decisions = game.decisions
    game.pauses.forEachIndexed { index, pause ->
        when (val request = pause.request) {
            is DecisionRequest.AssignTrampleDamage -> found += Mechanism.TRAMPLE_ASSIGNMENT
            is DecisionRequest.ChooseTargets ->
                if (hexproofConstrained(pause.state, request)) found += Mechanism.HEXPROOF_TARGETING
            else -> {}
        }
        val mechanismOfTakenOption = takenOptionMechanism(pause.request, decisions.getOrNull(index))
        if (mechanismOfTakenOption != null) found += mechanismOfTakenOption
    }
    return found
}

/**
 * The mechanism a [decision] revealed by the option it selected against [request], or `null` if none: a
 * madness reflexive cast accepted (its yes/no prompt names the madness cost, distinguishing it from Melded
 * Moxite's optional-discard yes/no), or a plotted card free-cast from exile (a Plot casting permission).
 */
private fun takenOptionMechanism(
    request: DecisionRequest,
    decision: Decision?,
): Mechanism? {
    if (decision !is Decision.SingleSelect) return null
    return when (request) {
        is DecisionRequest.ChooseYesNo ->
            if (request.prompt.contains("madness") && decision.index == DecisionRequest.ChooseYesNo.ACCEPT) {
                Mechanism.MADNESS_CAST
            } else {
                null
            }
        is DecisionRequest.ChooseAction -> {
            val option = request.options.getOrNull(decision.index)
            if (option is PriorityOption.CastSpell && option.permission is CastingPermission.Plot) {
                Mechanism.PLOT_CAST
            } else {
                null
            }
        }
        else -> null
    }
}

/**
 * Whether the targeting window [request] is constrained by hexproof: the caster's opponent controls a
 * battlefield creature with hexproof that is absent from the offered targets (CR 702.11e). Every targeted
 * spell in the MVP pool is an any-target burn spell that could otherwise hit a creature, so an opponent's
 * creature missing from the options is missing because of hexproof — the caster may never target it, while
 * their own hexproof creatures stay targetable (which is why this checks only the opponent's).
 */
private fun hexproofConstrained(
    state: GameState,
    request: DecisionRequest.ChooseTargets,
): Boolean {
    val opponent = opponentOf(request.seat)
    return state.sharedZones.battlefield.any { obj ->
        obj.owner == opponent &&
            hasHexproof(state, obj.card) &&
            Target.Permanent(obj.id) !in request.options
    }
}

/** Whether [card]'s printed characteristics include hexproof (CR 702.11) — intrinsic to the three one-drops. */
private fun hasHexproof(
    state: GameState,
    card: CardRef,
): Boolean =
    state.definitions[card]
        ?.characteristics
        ?.keywords
        ?.contains(Keyword.HEXPROOF) == true

/**
 * Accumulates, across a corpus, how many seeds exhibited each [Mechanism] — the census the corpus reports
 * and asserts its floors against (each mechanism at least once).
 */
internal class MechanismCensus {
    private val seedsExhibiting: MutableMap<Mechanism, Int> = ALL_MECHANISMS.associateWith { 0 }.toMutableMap()

    /** Records that one seed exhibited exactly [mechanisms]. */
    fun record(mechanisms: Set<Mechanism>) {
        mechanisms.forEach { seedsExhibiting[it] = seedsExhibiting.getValue(it) + 1 }
    }

    /** How many seeds exhibited [mechanism]. */
    fun countOf(mechanism: Mechanism): Int = seedsExhibiting.getValue(mechanism)

    /** The mechanisms no seed exhibited — the unmet floors (empty when the corpus meets every floor). */
    fun unmetFloors(): List<Mechanism> = ALL_MECHANISMS.filter { seedsExhibiting.getValue(it) == 0 }

    /** A one-line digest of every mechanism's seed count, for the packet report / CI log. */
    fun report(): String = ALL_MECHANISMS.joinToString(", ") { "${it.label}=${seedsExhibiting.getValue(it)}" }
}
