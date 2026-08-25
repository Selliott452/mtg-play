package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/*
 * Handcrafted-battlefield support for the P3.1 combat scenario suite. Creatures reach the
 * battlefield by construction (real creature cards and permanent-spell resolution are P3.2), so a
 * scenario builds the battlefield directly and drives the engine through the combat decisions.
 *
 * Every creature here is a synthetic fixture (a bare [CardDefinition]: printed types, P/T, and
 * keywords, no mana cost — these objects are never cast). Distinct names per scenario keep the
 * name-based decision helpers unambiguous.
 */

// A synthetic creature definition: creature-typed, with printed [power]/[toughness], [keywords], and
// [evasions] (P5.3; Silhana Ledgewalker's blockable-only-by-flying is the only member).
private fun creature(
    name: String,
    power: Int,
    toughness: Int,
    keywords: Set<Keyword> = emptySet(),
    evasions: Set<Evasion> = emptySet(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
                keywords = keywords.toPersistentSet(),
                evasions = evasions.toPersistentSet(),
            )
    }

// A `FW-PROTECT` creature fixture: printed [protections] (CR 702.16) and — its own half of the
// framework — an actual [manaCost].
//
// It is a sibling of [creature] rather than two more parameters on it because protection needs a
// *colour* on both sides and the plain fixtures have none. CR 202.2 derives colour from the mana
// cost, so a fixture that is the **source** of a protection or prevention test must carry one, where
// every other fixture here is costless and therefore colourless (CR 105.4) — which is itself a third
// source shape worth having. These objects are still never cast.
private fun protectionCreature(
    name: String,
    power: Int,
    toughness: Int,
    manaCost: String? = null,
    protections: Set<Quality> = emptySet(),
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = manaCost?.let(ManaCost::parse),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
                protections = protections.toPersistentSet(),
            )
    }

// An Aura fixture (P5.3): an enchant-creature permanent spell whose static ability generates
// [effect] (omitted for a trigger-only Aura, so the layer loud gate never sees an empty effect) and
// whose triggered abilities are [triggers]. Sorcery-speed, no-op resolution.
private fun aura(
    name: String,
    effect: StaticContinuousEffect? = null,
    triggers: List<TriggeredAbility> = emptyList(),
    manaCost: String = "{G}",
): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                // Green by default, as every P5.3 Aura fixture was. `FW-PROTECT` needs one that is a
                // *red* Aura so CR 704.5m's protection case is reachable: an Aura only falls off a
                // creature that has protection from the Aura's own quality.
                manaCost = ManaCost.parse(manaCost),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        override val resolution = ResolutionEffect { state, _ -> state }
        override val staticContinuousEffects = listOfNotNull(effect).toPersistentList()
        override val triggeredAbilities = triggers.toPersistentList()
    }

/** The scenario creature catalog, keyed by ref (the registry combat scenarios build states with). */
internal val combatDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        creature("Bear", 2, 2),
        creature("Ogre", 3, 3),
        creature("Giant", 4, 4),
        creature("Colossus", 6, 6),
        // A high-toughness, modest-power body: survives being ganged (its toughness exceeds its
        // blockers' combined power) so a damage-assignment-order scenario stays observable — the
        // attacker lives to show which blocker its assignment killed (P3.2).
        creature("Behemoth", 4, 8),
        creature("Wall", 0, 4),
        creature("Flyer", 2, 2, setOf(Keyword.FLYING)),
        creature("Raptor", 3, 3, setOf(Keyword.FLYING)),
        creature("Striker", 2, 2, setOf(Keyword.FIRST_STRIKE)),
        creature("Sentinel", 2, 2, setOf(Keyword.VIGILANCE)),
        // P5.3 keyword bodies. Trampler 5/5: over a 2/2 blocker its excess is 3; Charger 4/4 has
        // first strike too, so its trample assignment happens in the first-strike step. Lifelinker
        // 3/3. Skulker prints Silhana's evasion. Warden prints hexproof.
        creature("Trampler", 5, 5, setOf(Keyword.TRAMPLE)),
        creature("Charger", 4, 4, setOf(Keyword.FIRST_STRIKE, Keyword.TRAMPLE)),
        creature("Lifelinker", 3, 3, setOf(Keyword.LIFELINK)),
        // A 0-power lifelink body: an unblocked attack deals no damage, so lifelink gains nothing
        // (CR 702.15f — no damage dealt).
        creature("Meek", 0, 1, setOf(Keyword.LIFELINK)),
        creature("Skulker", 1, 1, evasions = setOf(Evasion.BLOCKABLE_ONLY_BY_FLYING)),
        // `W8-E`: Troll of Khazad-dum's "can't be blocked except by three or more creatures"
        // (CR 509.1b) — the first restriction on the blocker *count* rather than on a blocker's
        // characteristics, so it is legality of the whole declaration and not of any pairing.
        creature("Troll", 6, 5, evasions = setOf(Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE)),
        // `FW-COUNTERS` keyword bodies. Hasty attacks the turn it arrives (CR 702.10b); Bulwark is a
        // plain defender Wall (CR 702.3b); Sentry has defender *and* haste, the pair that proves the
        // two clauses are independent — haste lifts summoning sickness and defender still bars the
        // attack. Reacher blocks flyers (CR 702.17b) without flying itself.
        creature("Hasty", 2, 2, setOf(Keyword.HASTE)),
        creature("Bulwark", 0, 5, setOf(Keyword.DEFENDER)),
        creature("Sentry", 1, 4, setOf(Keyword.DEFENDER, Keyword.HASTE)),
        creature("Reacher", 2, 3, setOf(Keyword.REACH)),
        creature("Warden", 2, 2, setOf(Keyword.HEXPROOF)),
        // `FW-PROTECT` bodies (CR 702.16). The *protected* pair prints a quality each — "Warder" a
        // colour (Mask of Law and Grace's shape) and "Paladin" monocolored (Guardian of the
        // Guildpact's, the quality that is not a colour at all). The *source* trio exists to be
        // tested against: "Redcap" is mono-red, "Whitecap" mono-white, and "Hybrid" is two colours
        // and therefore neither red nor monocolored — the multicolored blind spot the printed card
        // famously has. Every other fixture is costless and so colourless (CR 105.4), which is a
        // third source shape for free.
        protectionCreature("Warder", 2, 2, protections = setOf(Quality.OfColor(Color.RED))),
        protectionCreature("Paladin", 2, 3, protections = setOf(Quality.Monocolored)),
        protectionCreature("Redcap", 2, 2, manaCost = "{R}"),
        protectionCreature("Whitecap", 2, 2, manaCost = "{W}"),
        protectionCreature("Hybrid", 2, 2, manaCost = "{R}{W}"),
        // P5.3 keyword-granting Auras (layer 6): hexproof, lifelink, and trample-with-+2/+0
        // (Rancor's shape). "Bloodfeast" is the Armadillo-Cloak analogue: a damage-triggered
        // gain-that-much-life for the Aura's controller — a trigger, distinct from the lifelink grant.
        // `FW-PROTECT` Auras (CR 613.1f layer 6, CR 702.16). "Ward Aura" is green and grants
        // protection from red — Mask of Law and Grace's shape, and it removes neither itself nor any
        // other green Aura. "Red Aura" is red and grants nothing: it is the CR 704.5m self-removal
        // case, which no *in-pool* card reaches (a property of two decklists, not of the rules) and
        // which the SBA is nevertheless written for (docs/design/protection.md §2.2).
        aura("Ward Aura", StaticContinuousEffect(grantedProtections = persistentSetOf(Quality.OfColor(Color.RED)))),
        aura(
            "Red Aura",
            StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.VIGILANCE)),
            manaCost = "{R}",
        ),
        aura("Hex Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.HEXPROOF))),
        aura("Vamp Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.LIFELINK))),
        // `FW-COUNTERS` keyword-granting Auras (layer 6), so each new keyword is exercised through the
        // effective-keyword seam and not only as a printed characteristic.
        aura("Haste Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.HASTE))),
        aura("Reach Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.REACH))),
        aura("Wall Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.DEFENDER))),
        aura(
            "Fixture Rancor",
            StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.TRAMPLE), powerMod = Magnitude.Fixed(2)),
        ),
        aura(
            "Bloodfeast",
            triggers =
                listOf(
                    TriggeredAbility(
                        condition = TriggerCondition.EnchantedCreatureDealsDamage,
                        effect =
                            ResolutionEffect {
                                state,
                                context,
                                ->
                                gainLife(state, context.controller, context.amount)
                            },
                    ),
                ),
        ),
        // Keyword-tail bodies. "Venomous" is a plain 2/2 deathtoucher: one point from it is lethal to
        // anything (CR 702.2b). "Venomtrampler" pairs deathtouch with trample, which is where the two
        // interact — CR 702.19b excess is power minus *1* per blocker, not power minus toughness.
        // "Ironhide" is the CR 702.12b control: dealt deathtouch damage and correctly not destroyed.
        creature("Venomous", 2, 2, setOf(Keyword.DEATHTOUCH)),
        creature("Venomtrampler", 4, 4, setOf(Keyword.DEATHTOUCH, Keyword.TRAMPLE)),
        creature("Ironhide", 2, 2, setOf(Keyword.INDESTRUCTIBLE)),
        // "Ghost" prints the haste evasion so block legality is testable without activating anything;
        // Gingerbrute's own copy is granted, and DurationSpec covers that half through the layer seam.
        creature("Ghost", 1, 1, evasions = setOf(Evasion.BLOCKABLE_ONLY_BY_HASTE)),
        // "Mimic" is the changeling fixture: it prints *no* subtype at all, so every subtype it
        // matches comes from CR 702.73a and nothing else.
        creature("Mimic", 2, 2, setOf(Keyword.CHANGELING)),
        // A granted deathtouch (CR 613.1f layer 6), so the keyword is exercised through the effective
        // seam and not only as a printed characteristic — the shape Toxin Analysis actually has.
        aura("Venom Aura", StaticContinuousEffect(grantedKeywords = persistentSetOf(Keyword.DEATHTOUCH))),
        // `FW-CONDSTATIC` fixtures. "Raider" is Goblin Tomb Raider's shape: a conditional static
        // ability affecting its own source (CR 604.3, AffectedSet.Self). "Trinket" is a bare
        // noncreature artifact — the permanent whose presence switches the condition on and off.
        object : CardDefinition {
            override val characteristics =
                PrintedCharacteristics(
                    name = "Raider",
                    manaCost = null,
                    supertypes = persistentSetOf(),
                    cardTypes = persistentSetOf(CardType.CREATURE),
                    subtypes = persistentSetOf(),
                    powerToughness = PrintedPowerToughness(1, 2),
                )
            override val staticContinuousEffects =
                persistentListOf(
                    StaticContinuousEffect(
                        affects = AffectedSet.Self,
                        condition =
                            StaticCondition.YouControl(
                                filter = PermanentFilter(cardType = CardType.ARTIFACT, controlledByYou = true),
                            ),
                        grantedKeywords = persistentSetOf(Keyword.HASTE),
                        powerMod = Magnitude.Fixed(1),
                    ),
                )
        },
        object : CardDefinition {
            override val characteristics =
                PrintedCharacteristics(
                    name = "Trinket",
                    manaCost = null,
                    supertypes = persistentSetOf(),
                    cardTypes = persistentSetOf(CardType.ARTIFACT),
                    subtypes = persistentSetOf(),
                    powerToughness = null,
                )
        },
    ).associateBy { CardRef(it.characteristics.name) }

/**
 * One handcrafted battlefield creature. [summoningSick] defaults to `false` — combat scenarios
 * usually want ready attackers; set it `true` to exercise the CR 302.6 exclusion.
 */
internal data class Combatant(
    val name: String,
    val tapped: Boolean = false,
    val summoningSick: Boolean = false,
    val damageMarked: Int = 0,
    /** Counters the creature enters the scenario with (CR 122.1) — `FW-COUNTERS`. */
    val counters: Map<Counter, Int> = emptyMap(),
)

/**
 * A handcrafted, paused two-player [GameState] with the combat creature registry: [aliceField] and
 * [bobField] on the battlefield (ids allocated alice-first, in order), each seat on 20 life with a
 * few inert Mountains in library so an incidental draw never decks a scenario out, and [holder]
 * mid-priority (or no holder — the paused-at-a-turn-based-action shape combat uses at
 * declare-attackers/blockers).
 */
internal fun handcraftedCombat(
    turn: Turn,
    aliceField: List<Combatant> = emptyList(),
    bobField: List<Combatant> = emptyList(),
    holder: PlayerId? = null,
): GameState {
    var nextId = 0L

    fun objects(
        field: List<Combatant>,
        owner: PlayerId,
    ): List<GameObject> =
        field.map { spec ->
            GameObject(
                id = ObjectId(nextId++),
                card = CardRef(spec.name),
                owner = owner,
                tapped = spec.tapped,
                damageMarked = spec.damageMarked,
                summoningSick = spec.summoningSick,
                counters = spec.counters.toPersistentMap(),
            )
        }

    val aliceObjects = objects(aliceField, alice)
    val bobObjects = objects(bobField, bob)

    fun seat(owner: PlayerId): PlayerState =
        PlayerState(
            life = STARTING_LIFE,
            library = List(3) { GameObject(ObjectId(nextId++), CardRef("Mountain"), owner) }.toPersistentList(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )

    val alicePlayer = seat(alice)
    val bobPlayer = seat(bob)
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = (aliceObjects + bobObjects).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = combatDefinitions.toPersistentMap(),
    )
}

/** A state paused at the active player's declare-attackers turn-based action (CR 508.1). */
internal fun attackStep(
    aliceField: List<Combatant> = emptyList(),
    bobField: List<Combatant> = emptyList(),
    active: PlayerId = alice,
): GameState =
    handcraftedCombat(
        turn = Turn(active, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
        aliceField = aliceField,
        bobField = bobField,
    )

/** A sampled turn number that is neither turn 1 (no draw-step skip interactions) nor a boundary. */
internal const val TURN_NUMBER: Int = 3

/**
 * A battlefield object over [combatDefinitions] — the escape hatch for the P5.3 keyword scenarios
 * that need Auras attached to creatures, which [handcraftedCombat]'s creature-only field lists cannot
 * express. Not summoning sick by default (combat scenarios want ready attackers).
 */
internal fun combatObject(
    id: Long,
    name: String,
    owner: PlayerId,
    attachedTo: Long? = null,
    summoningSick: Boolean = false,
): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(name),
        owner = owner,
        summoningSick = summoningSick,
        attachedTo = attachedTo?.let(::ObjectId),
    )

/**
 * A paused two-player [GameState] over [combatDefinitions] with [battlefield] in place, both seats at
 * 20 life. [holder] (if given) is mid-priority. The id counter sits just past the highest id. Used by
 * the P5.3 targeting and aura-attached combat scenarios; [turn] defaults to a precombat main.
 */
internal fun keywordState(
    battlefield: List<GameObject>,
    turn: Turn = Turn(alice, TURN_NUMBER, TurnPhase.PRECOMBAT_MAIN, null),
    holder: PlayerId? = null,
): GameState {
    var nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun seat(owner: PlayerId) =
        PlayerState(
            life = STARTING_LIFE,
            library =
                List(3) { GameObject(ObjectId(nextId++), CardRef("Mountain"), owner) }.toPersistentList(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )
    val alicePlayer = seat(alice)
    val bobPlayer = seat(bob)
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = combatDefinitions.toPersistentMap(),
    )
}

/** The battlefield object with card [name] owned by [owner] (P5.3 scenarios place same-card objects per seat). */
internal fun GameState.creatureOf(
    name: String,
    owner: PlayerId,
): GameObject = sharedZones.battlefield.first { it.card == CardRef(name) && it.owner == owner }

// --- Decision helpers (name-based; scenario creature names are distinct within a request) ---

/** Declares the named creatures as attackers (CR 508.1); no names is the empty, legal declaration. */
internal fun DecisionRequest.DeclareAttackers.declaring(vararg names: String): Decision.MultiSelect =
    Decision.MultiSelect(id, names.map { name -> optionIndex(name) })

/** The eligible-attacker card names, in option order (for enumeration-completeness assertions). */
internal fun DecisionRequest.DeclareAttackers.attackerNames(): List<String> = options.map { it.card.name }

private fun DecisionRequest.DeclareAttackers.optionIndex(name: String): Int =
    options.indexOfFirst { it.card == CardRef(name) }.also {
        require(it >= 0) { "no eligible attacker named $name in ${attackerNames()}" }
    }

/** Declares the given (blocker name -> attacker name) blocks (CR 509.1); no pairs blocks nothing. */
internal fun DecisionRequest.DeclareBlockers.blocking(vararg pairs: Pair<String, String>): Decision.MultiSelect =
    Decision.MultiSelect(
        id,
        pairs.map { (blocker, attacker) ->
            options.indexOfFirst { it.blockerCard == CardRef(blocker) && it.attackerCard == CardRef(attacker) }.also {
                require(it >= 0) { "no legal block of $attacker by $blocker in ${blockPairs()}" }
            }
        },
    )

/** The legal (blocker, attacker) pairings by name (for enumeration assertions). */
internal fun DecisionRequest.DeclareBlockers.blockPairs(): List<Pair<String, String>> =
    options.map { it.blockerCard.name to it.attackerCard.name }

/** Orders this attacker's blockers by name — the permutation damage is assigned in (CR 509.2). */
internal fun DecisionRequest.OrderBlockers.ordering(vararg names: String): Decision.MultiSelect =
    Decision.MultiSelect(
        id,
        names.map { name ->
            options.indexOfFirst { it.card == CardRef(name) }.also {
                require(it >= 0) { "no blocker named $name to order in ${options.map { o -> o.card.name }}" }
            }
        },
    )

/** The battlefield creature with the given card [name]; scenario names are distinct there. */
internal fun GameState.creature(name: String): GameObject = sharedZones.battlefield.first { it.card == CardRef(name) }

/**
 * Passes the open two-player priority window to its end (both players pass in succession,
 * CR 117.4) and returns the next pause — a combat scenario's way to step over the priority round
 * that follows each combat sub-action, onto the next combat decision or step.
 */
internal fun DefaultGameEngine.passPriorityRound(result: AdvanceResult): AdvanceResult {
    val afterActive = advance(result.pausedState, passDecision(result.pending()))
    return advance(afterActive.pausedState, passDecision(afterActive.pending()))
}

/**
 * Drives forward with the do-nothing policy — pass every priority window, declare no attackers or
 * blockers, discard the lowest-indexed cards — until [until] holds of the paused state. A blunt
 * walker for reaching a later turn position from a handcrafted start.
 */
internal fun DefaultGameEngine.driveByPassing(
    from: AdvanceResult,
    until: (GameState) -> Boolean,
): AdvanceResult {
    var result = from
    var guard = 0
    while (!until(result.pausedState)) {
        check(guard++ < DRIVE_GUARD) { "driveByPassing did not reach its predicate within $DRIVE_GUARD steps" }
        result =
            when (val request = result.pending<DecisionRequest>()) {
                is DecisionRequest.ChooseAction -> advance(result.pausedState, passDecision(request))
                is DecisionRequest.DeclareAttackers -> advance(result.pausedState, request.declaring())
                is DecisionRequest.DeclareBlockers -> advance(result.pausedState, request.blocking())
                is DecisionRequest.ChooseDiscards -> {
                    val all = (0 until request.count).toList()
                    advance(result.pausedState, Decision.MultiSelect(request.id, all))
                }
                else -> error("driveByPassing does not handle $request")
            }
    }
    return result
}

private const val DRIVE_GUARD: Int = 200

/** Declares the named [attackers] from a declare-attackers-paused [state] (CR 508.1). */
internal fun DefaultGameEngine.declareAttackers(
    state: GameState,
    vararg attackers: String,
): AdvanceResult = advance(state, pausedRequestOf<DecisionRequest.DeclareAttackers>(state).declaring(*attackers))

/**
 * Declares the named [attackers] and passes the ensuing priority round, returning the pause at the
 * defending player's declare-blockers decision (CR 509.1) — the common preamble of the scenarios.
 */
internal fun DefaultGameEngine.toDeclareBlockers(
    state: GameState,
    vararg attackers: String,
): AdvanceResult = passPriorityRound(declareAttackers(state, *attackers))

/** Declares the given (blocker -> attacker) blocks from a [from] declare-blockers pause. */
internal fun DefaultGameEngine.declareBlocks(
    from: AdvanceResult,
    vararg pairs: Pair<String, String>,
): AdvanceResult = advance(from.pausedState, from.pending<DecisionRequest.DeclareBlockers>().blocking(*pairs))
