package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's nonbasic lands whose whole printed text is mana production plus — at most — the
 * CR 614.1c "this land enters tapped" self-replacement: the three Mirrodin artifact lands, the four
 * Modern Horizons Bridges, and Idyllic Beachfront.
 *
 * Like the basics (BasicLands.kt) each is *played*, not cast (CR 305.1, CR 116.2a), so every one is a
 * plain [CardDefinition] and never a [dev.mtgplay.core.definition.SpellDefinition].
 *
 * **Intrinsic abilities from land types are still authored explicitly** (the P2.2 architect decision
 * recorded in BasicLands.kt): Idyllic Beachfront's type line "Land — Plains Island" grants it
 * "{T}: Add {W}" and "{T}: Add {U}" by CR 305.6, and those are written out here as two abilities rather
 * than derived, exactly as Mountain's `{T}: Add {R}` is. The Bridges' and artifact lands' abilities are
 * printed rules text, not type-derived, so no such question arises for them.
 *
 * Every mechanism used is a published primitive (ADR-003); the nonbasic lands of the gauntlet that need
 * more than these — a colour chosen as the land enters and then produced, two mana from one activation,
 * a targeted enters-the-battlefield trigger, a search that puts a land onto the battlefield, surveil, a
 * costed mana ability, a conditional enters-tapped clause — are deliberately absent rather than
 * approximated.
 */

/**
 * Great Furnace — Artifact Land. "{T}: Add {R}." An artifact *and* a land (CR 301, CR 305): it is
 * played as a land (CR 305.1) and is an artifact on the battlefield, which is why the affinity decks
 * count it. One intrinsic mana ability (CR 605.1a).
 */
val greatFurnace: CardDefinition = artifactLand(name = "Great Furnace", produces = ManaType.RED)

/**
 * Seat of the Synod — Artifact Land. "{T}: Add {U}." [greatFurnace]'s blue counterpart.
 */
val seatOfTheSynod: CardDefinition = artifactLand(name = "Seat of the Synod", produces = ManaType.BLUE)

/**
 * Vault of Whispers — Artifact Land. "{T}: Add {B}." [greatFurnace]'s black counterpart.
 */
val vaultOfWhispers: CardDefinition = artifactLand(name = "Vault of Whispers", produces = ManaType.BLACK)

/**
 * Drossforge Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {B} or {R}."
 * The black-red Bridge: a dual-producing artifact land whose cost for the fixing is entering tapped
 * (CR 614.1c, [CardDefinition.entersTapped]). Its printed indestructible (CR 702.12) is a
 * characteristic here; the engine honours it wherever it destroys — the CR 704.5g lethal-damage
 * state-based action, which a land never reaches, and the CR 701.7a destroy effect, which is what
 * makes the keyword matter: an opposing [ancientGrudge] or [smashToSmithereens] targets a Bridge
 * legally and destroys nothing (CR 702.12b).
 */
val drossforgeBridge: CardDefinition = bridge(name = "Drossforge Bridge", ManaType.BLACK, ManaType.RED)

/**
 * Mistvault Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {U} or {B}."
 * The blue-black [drossforgeBridge].
 */
val mistvaultBridge: CardDefinition = bridge(name = "Mistvault Bridge", ManaType.BLUE, ManaType.BLACK)

/**
 * Silverbluff Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {U} or {R}."
 * The blue-red [drossforgeBridge].
 */
val silverbluffBridge: CardDefinition = bridge(name = "Silverbluff Bridge", ManaType.BLUE, ManaType.RED)

/**
 * Slagwoods Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {R} or {G}."
 * The red-green [drossforgeBridge].
 */
val slagwoodsBridge: CardDefinition = bridge(name = "Slagwoods Bridge", ManaType.RED, ManaType.GREEN)

/**
 * Idyllic Beachfront — Land — Plains Island. "({T}: Add {W} or {U}.) This land enters tapped."
 *
 * The whole card is its type line plus one clause. It has no *printed* mana ability at all — the
 * parenthesised text is reminder text for what CR 305.6 gives any permanent with the Plains and Island
 * land types: the two separate intrinsic abilities "{T}: Add {W}" and "{T}: Add {U}". They are written
 * out as two [ManaAbility] entries, which is the faithful shape (two abilities, not one ability with a
 * choice) and behaves identically, both costing `{T}` on a permanent that can only be tapped once.
 *
 * It has neither the Basic supertype nor any printed keyword; the only rules text is the CR 614.1c
 * "this land enters tapped" ([CardDefinition.entersTapped]).
 */
val idyllicBeachfront: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Idyllic Beachfront",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Plains"), Subtype("Island")),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(
                ManaAbility(persistentListOf(ManaType.WHITE)),
                ManaAbility(persistentListOf(ManaType.BLUE)),
            )
        override val entersTapped = EntersTapped.Always
    }

/**
 * An artifact land (CR 301, CR 305): both card types, no supertype and no land subtype, and one
 * printed intrinsic mana ability adding [produces] (CR 605.1a).
 */
private fun artifactLand(
    name: String,
    produces: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(produces)))
    }

/**
 * A Bridge artifact land: an indestructible artifact land that enters tapped and has the single
 * printed mana ability "{T}: Add [first] or [second]" — one ability offering a choice, so its two
 * options ride in one [ManaAbility]. The options are listed in the WUBRG-then-colorless order the
 * payment enumerator treats as canonical (docs/design/mana-payment.md).
 */
private fun bridge(
    name: String,
    first: ManaType,
    second: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
                keywords = persistentSetOf(Keyword.INDESTRUCTIBLE),
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(first, second)))
        override val entersTapped = EntersTapped.Always
    }
