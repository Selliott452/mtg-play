package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The basic lands of the MVP pool (CR 305). A land is *played*, not cast (CR 305.1, CR 116.2a),
 * so a basic-land definition implements only [CardDefinition] — never `SpellDefinition` — and
 * enters the game through the play-land special action in `mtg-rules`.
 *
 * **Architect decision (P2.2):** each basic land's intrinsic tap-for-mana ability is authored
 * explicitly on its definition rather than derived from its basic land subtype per CR 305.6.
 * Nothing in the MVP pool changes an object's land types, so subtype-derived intrinsic
 * abilities are deferred complexity; when a type-changing effect first arrives, derivation
 * lands in the rules engine's characteristics layer, not by rewriting these definitions.
 */

/**
 * Mountain — basic land (CR 305): the Basic supertype, the Land card type, the Mountain land
 * subtype, no mana cost, and the intrinsic ability `{T}: Add {R}` (authored explicitly; see the
 * file note on CR 305.6).
 */
val mountain: CardDefinition = basicLand(name = "Mountain", produces = ManaType.RED)

/**
 * Forest — basic land (CR 305): the Basic supertype, the Land card type, the Forest land
 * subtype, no mana cost, and the intrinsic ability `{T}: Add {G}` (authored explicitly; see the
 * file note on CR 305.6).
 */
val forest: CardDefinition = basicLand(name = "Forest", produces = ManaType.GREEN)

/**
 * Plains — basic land (CR 305): the Basic supertype, the Land card type, the Plains land
 * subtype, no mana cost, and the intrinsic ability `{T}: Add {W}` (authored explicitly; see the
 * file note on CR 305.6).
 */
val plains: CardDefinition = basicLand(name = "Plains", produces = ManaType.WHITE)

/**
 * A basic land definition: for the five basics the land subtype equals the printed name
 * (CR 205.3i), and the single intrinsic mana ability adds one mana of [produces] (CR 605.1a).
 */
private fun basicLand(
    name: String,
    produces: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(Supertype.BASIC),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype(name)),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(produces)))
    }
