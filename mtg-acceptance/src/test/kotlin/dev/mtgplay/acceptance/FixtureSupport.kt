package dev.mtgplay.acceptance

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.effect.loseLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Fixture definitions for the acceptance suites — the acceptance twin of the mtg-rules test
 * fixtures (module test sources cannot be shared; the small duplication is deliberate until
 * real cards arrive in P2.2). A fixture match is lands + fixture bolts, with the battlefield
 * seeded directly because the play-land action is P2.2's: [fixtureMatchStart] doctors the
 * engine's own starting state, a pure function of the seed, so replays can rebuild it exactly.
 */

/** What resolving a Fixture Bolt costs its target (CR 119.3c via the lose-life primitive). */
internal const val FIXTURE_BOLT_LIFE_LOSS: Int = 3

/**
 * Turn cap for fixture playouts: bolts only shorten games relative to the lands-only bound
 * (deck-out near turn 108), so the lands-only cap carries over.
 */
internal const val FIXTURE_TURN_CAP: Int = 130

/** "Fixture Mountain" — a land source: `{T}: add {R}` (CR 605.1a). */
internal val fixtureMountain: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Mountain",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.RED)))
    }

/** "Fixture Bolt" — `{R}` instant, any target: the targeted player loses 3 life. */
internal val fixtureBolt: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fixture Bolt",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                when (val target = context.targets.single()) {
                    is Target.Player -> loseLife(state, target.id, FIXTURE_BOLT_LIFE_LOSS)
                }
            }
    }

/** The acceptance fixture registry: bolts are castable, fixture Mountains are mana sources. */
internal val fixtureDefinitions: Map<CardRef, CardDefinition> =
    mapOf(
        CardRef("Fixture Mountain") to fixtureMountain,
        CardRef("Fixture Bolt") to fixtureBolt,
    )

/** A fixture deck: [bolts] Fixture Bolts then lands to [size] cards (CR 100.1). */
internal fun fixtureDeck(
    bolts: Int = 20,
    size: Int = DECK_SIZE,
): List<CardRef> = List(bolts) { CardRef("Fixture Bolt") } + List(size - bolts) { CardRef("Fixture Mountain") }

/** The two-seat fixture match config: fixture decks plus the fixture definitions (ADR-006). */
internal fun fixtureConfig(
    seed: Long,
    startingPlayer: PlayerId? = null,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to fixtureDeck(), bob to fixtureDeck()),
        definitions = fixtureDefinitions,
        startingPlayer = startingPlayer,
    )

/**
 * Starts a fixture match and doctors its first paused state: [battlefieldPerSeat] untapped
 * Fixture Mountains are planted on the battlefield per seat, with properly allocated ids
 * (CR 400.7). A pure function of the seed — replaying a recorded fixture game rebuilds this
 * exact state and feeds the same decisions (ADR-006). The doctoring stands in for the
 * play-land action until P2.2 delivers it.
 */
internal fun fixtureMatchStart(
    seed: Long,
    battlefieldPerSeat: Int = 4,
    startingPlayer: PlayerId? = null,
): GameState {
    val first = DefaultGameEngine().start(fixtureConfig(seed, startingPlayer))
    var state =
        when (first) {
            is AdvanceResult.NeedsDecision -> first.state
            is AdvanceResult.GameOver -> error("a fixture match cannot be over before its first decision")
        }
    for (seat in state.players.keys.sortedBy(PlayerId::seat)) {
        repeat(battlefieldPerSeat) {
            val (id, allocated) = state.allocateObjectId()
            val source = GameObject(id, CardRef("Fixture Mountain"), seat)
            state =
                allocated.copy(
                    sharedZones =
                        allocated.sharedZones.copy(
                            battlefield = allocated.sharedZones.battlefield.adding(source),
                        ),
                )
        }
    }
    return state
}
