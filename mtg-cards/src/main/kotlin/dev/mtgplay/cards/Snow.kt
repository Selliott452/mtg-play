package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.dealDamage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's snow cards: the three Snow-Covered basics Jeskai Ephemerate's mana base is built
 * from, and [skred], the one card in the whole thirteen-deck gauntlet that cares (docs/decklists.md).
 *
 * **Snow is not a framework here, and this file is the evidence.** `Supertype.SNOW` (CR 205.4a)
 * already existed in `mtg-core` before this packet, and **no gauntlet card uses `{S}` snow mana at
 * all** — no printed mana cost and no printed ability in the pinned Scryfall snapshot contains the
 * symbol. Snow-matters is therefore exactly two things: a supertype on a permanent, and a card that
 * counts permanents carrying it. Neither needs an engine change.
 *
 * Like the other lands (BasicLands.kt, NonbasicLands.kt) each Snow-Covered basic is *played*, not
 * cast (CR 305.1, CR 116.2a), so it is a plain [CardDefinition]; and per the P2.2 architect decision
 * recorded in BasicLands.kt its CR 305.6 intrinsic mana ability is authored explicitly rather than
 * derived from its land subtype.
 */

/**
 * Snow-Covered Island — `Basic Snow Land — Island`. "({T}: Add {U}.)"
 *
 * A CR 205.4a **two-supertype** card: Basic *and* Snow, which is why its name and its land subtype
 * differ (CR 205.3i's name-equals-subtype convenience holds for the five plain basics only). The
 * printed text is nothing but reminder text for the CR 305.6 ability its Island type grants; the
 * Snow supertype adds no ability whatever, and is read only by cards that count snow permanents
 * ([skred]).
 *
 * Being Basic, it is exempt from the four-of deck-construction limit and is a legal find for
 * "search for a basic land card" — `CardMetadata.isBasic` reads the supertype off the type line and
 * already answers `true` here, with no change needed for the extra Snow word.
 */
val snowCoveredIsland: CardDefinition =
    snowBasicLand(name = "Snow-Covered Island", subtype = "Island", produces = ManaType.BLUE)

/**
 * Snow-Covered Mountain — `Basic Snow Land — Mountain`. "({T}: Add {R}.)" [snowCoveredIsland]'s red
 * counterpart.
 */
val snowCoveredMountain: CardDefinition =
    snowBasicLand(name = "Snow-Covered Mountain", subtype = "Mountain", produces = ManaType.RED)

/**
 * Snow-Covered Plains — `Basic Snow Land — Plains`. "({T}: Add {W}.)" [snowCoveredIsland]'s white
 * counterpart.
 */
val snowCoveredPlains: CardDefinition =
    snowBasicLand(name = "Snow-Covered Plains", subtype = "Plains", produces = ManaType.WHITE)

/**
 * Skred — `{R}` Instant. "Skred deals damage to target creature equal to the number of snow
 * permanents you control."
 *
 * Two clauses, neither of them a framework:
 * - **"target creature"** (CR 115.1a) is [TargetSpec.TargetCreature] — narrower than the any-target
 *   of a Lightning Bolt, because no player is ever a legal choice. It is the reason this card cannot
 *   go to the face, and it makes the CR 608.2b fizzle reachable: kill the target in response and the
 *   spell does not resolve at all;
 * - **"equal to the number of snow permanents you control"** is a state-dependent *amount*, not a
 *   framework. A [ResolutionEffect] is already a pure function of the [GameState] (ADR-004), so the
 *   count is taken as the spell resolves (CR 608.2) — the [galvanicBlast] precedent exactly. A
 *   Snow-Covered Island that leaves the battlefield in response shrinks the damage; one played in
 *   response could not, since the spell is already on the stack, but one *untapped* still counts:
 *   the clause counts permanents, not mana, and tapped-ness is irrelevant to it.
 *
 * "Snow permanents" is every permanent with the Snow supertype (CR 205.4a) under any card type — the
 * predicate deliberately does not say "land", because the printed text does not. "You control" is
 * ownership in the MVP pool (docs/design/layer-system.md §4). With no snow permanent at all the
 * count is zero, and CR 120.8 makes that no damage rather than an error — Skred still resolves and
 * still fizzles if its target has gone.
 */
val skred: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Skred",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetCreature
        override val resolution =
            ResolutionEffect { state, context ->
                val snowPermanents =
                    state.sharedZones.battlefield.count {
                        it.owner == context.controller && isSnow(state, it)
                    }
                dealDamage(state, context.targets.single(), snowPermanents)
            }
    }

/**
 * Whether the battlefield object [obj] has the Snow supertype (CR 205.4a); an inert object — a
 * [dev.mtgplay.core.identity.CardRef] with no definition in the state — has no supertype and is
 * never snow. Read from printed characteristics, as every other type test in the pool is: nothing in
 * the gauntlet changes a permanent's supertypes.
 */
private fun isSnow(
    state: GameState,
    obj: GameObject,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.supertypes
        ?.contains(Supertype.SNOW) == true

/**
 * A Snow-Covered basic land (CR 305, CR 205.4a): both the Basic and Snow supertypes, the Land card
 * type, the [subtype] land type — which is *not* the printed name here — and the single intrinsic
 * mana ability adding one mana of [produces] (CR 605.1a, CR 305.6).
 */
private fun snowBasicLand(
    name: String,
    subtype: String,
    produces: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(Supertype.BASIC, Supertype.SNOW),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype(subtype)),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(produces)))
    }
