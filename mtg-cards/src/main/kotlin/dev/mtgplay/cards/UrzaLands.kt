package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Monster Tron's engine: the three Urza lands, each adding one colorless alone and more when the
 * other two are on the battlefield beside it (CR 605.2). Twelve of the deck's twenty-two lands.
 *
 * **The condition is on land subtypes, not on card names, and the subtypes are hyphenated.** The
 * type lines read `Land — Urza's Mine`, `Land — Urza's Power-Plant` and `Land — Urza's Tower`
 * (CR 205.3i), while the middle card's *name* is "Urza's Power Plant" with no hyphen. The oracle
 * text says "If you control an Urza's Power-Plant and an Urza's Tower", which is the **subtype**;
 * matching on the name instead would be silently wrong in the direction that never throws — the
 * count would simply always come up short and Tron would never assemble.
 *
 * Each is *played*, not cast (CR 305.1, CR 116.2a), so all three are plain [CardDefinition]s. Their
 * amounts differ and the difference is printed: the Tower adds **three**, the Mine and the Power
 * Plant add two.
 */

/** `Land — Urza's Mine`'s subtype (CR 205.3i). */
private val URZAS_MINE = Subtype("Urza's Mine")

/** `Land — Urza's Power-Plant`'s subtype — hyphenated, unlike the card's name (CR 205.3i). */
private val URZAS_POWER_PLANT = Subtype("Urza's Power-Plant")

/** `Land — Urza's Tower`'s subtype (CR 205.3i). */
private val URZAS_TOWER = Subtype("Urza's Tower")

/**
 * Urza's Mine — *Land — Urza's Mine.* "{T}: Add {C}. If you control an Urza's Power-Plant and an
 * Urza's Tower, add {C}{C} instead."
 *
 * The first card in the pool whose production depends on the board. Note what does **not** happen:
 * the condition is read when the mana ability resolves (CR 605.2), so a Tron assembled after the
 * total cost was locked in still funds the payment, and a cost reduced under CR 601.2f is not
 * re-reduced because the Mine turned out to add two. The two rules are independent
 * (docs/design/cost-modification.md §8).
 */
val urzasMine: CardDefinition =
    urzaLand(
        name = "Urza's Mine",
        subtype = URZAS_MINE,
        requires = persistentListOf(URZAS_POWER_PLANT, URZAS_TOWER),
        assembled = URZA_MINE_ASSEMBLED_MANA,
    )

/**
 * Urza's Power Plant — *Land — Urza's Power-Plant.* "{T}: Add {C}. If you control an Urza's Mine and
 * an Urza's Tower, add {C}{C} instead." [urzasMine]'s counterpart; note the name has no hyphen and
 * the subtype does.
 */
val urzasPowerPlant: CardDefinition =
    urzaLand(
        name = "Urza's Power Plant",
        subtype = URZAS_POWER_PLANT,
        requires = persistentListOf(URZAS_MINE, URZAS_TOWER),
        assembled = URZA_MINE_ASSEMBLED_MANA,
    )

/**
 * Urza's Tower — *Land — Urza's Tower.* "{T}: Add {C}. If you control an Urza's Mine and an Urza's
 * Power-Plant, add **{C}{C}{C}** instead."
 *
 * The odd one out, and the reason the triage flagged the upstream brief: the Tower adds **three**,
 * not two. Assembled Tron is therefore `2 + 2 + 3 = 7` colorless from three activations, which is
 * exactly Maelstrom Colossus's `{5}{G}{G}`-shaped price bracket and the deck's whole plan.
 */
val urzasTower: CardDefinition =
    urzaLand(
        name = "Urza's Tower",
        subtype = URZAS_TOWER,
        requires = persistentListOf(URZAS_MINE, URZAS_POWER_PLANT),
        assembled = URZA_TOWER_ASSEMBLED_MANA,
    )

/** What an Urza's Mine or Urza's Power-Plant adds with the other two Urza lands out. */
private const val URZA_MINE_ASSEMBLED_MANA: Int = 2

/** What an Urza's Tower adds with the other two Urza lands out — one more than its siblings. */
private const val URZA_TOWER_ASSEMBLED_MANA: Int = 3

/** What any Urza land adds on its own. */
private const val URZA_UNASSEMBLED_MANA: Int = 1

/**
 * One Urza land: a colorless-producing land with [subtype] whose single mana ability
 * (CR 605.1a) adds [assembled] mana while its controller has a permanent of each subtype in
 * [requires], and [URZA_UNASSEMBLED_MANA] otherwise (CR 605.2).
 */
private fun urzaLand(
    name: String,
    subtype: Subtype,
    requires: PersistentList<Subtype>,
    assembled: Int,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(subtype),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.COLORLESS),
                    amount =
                        ManaAmount.Conditional(
                            // "If you control an …": the filters are controller-scoped.
                            requires = requires.map { PermanentFilter(it, controlledByYou = true) }.toPersistentList(),
                            ifMet = assembled,
                            otherwise = URZA_UNASSEMBLED_MANA,
                        ),
                ),
            )
    }
