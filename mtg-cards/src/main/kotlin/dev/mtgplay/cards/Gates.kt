package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AsEntersColorChoice
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.manaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The Gates deck's colour-fixing Gate cycle (`W8-A`) — the *Baldur's Gate* lands whose printed text is
 * three clauses, one of which no land in the pool had printed before:
 *
 *   This land enters tapped.
 *   As this land enters, choose a color other than <its own colour>.
 *   {T}: Add <its own colour> or one mana of the chosen color.
 *
 * The first clause is the CR 614.1c self-replacement [EntersTapped.Always] the Bridges already print
 * (NonbasicLands.kt). The second is a CR 614.12 **as-enters choice** — Utopia Sprawl's, with an
 * exclusion on the option list, which is what widened `CardDefinition.asEntersColorChoice` from a
 * flag into [AsEntersColorChoice]. The third reads that stored choice back out
 * ([ManaAbility.includesChosenColor]), which is the first time an *intrinsic* mana ability has depended
 * on a per-object fact rather than on the card alone.
 *
 * **Two engine additions carry the cycle, and both are recorded where they live**: the widened as-enters
 * choice (and its restricted option list), and the play-land pause for it. A land is *played*, never cast
 * (CR 305.1, CR 116.2a), so before this packet the CR 614.12 flow existed only inside a resolving
 * permanent *spell* — Utopia Sprawl is an Aura. `executePlayLand` now pauses for the choice before the
 * land joins the battlefield, exactly where CR 614.12 puts it (PlayLand.kt).
 *
 * **Basilisk Gate is a Gate and is not here.** It lives in NonbasicLands.kt, where it landed with the
 * rest of the P8.4 mana base and where its own `+X/+X` machinery is documented; it prints neither of this
 * cycle's two distinguishing clauses, so moving it would buy nothing but churn. It does still count
 * itself and each of these three for its "number of Gates you control", which is why the Gates deck runs
 * both.
 */

/** The land type this cycle carries, and the type Basilisk Gate counts (CR 205.3i). */
private val GATE: Subtype = Subtype("Gate")

/**
 * Citadel Gate — Land — Gate. "This land enters tapped. As this land enters, choose a color other than
 * white. {T}: Add {W} or one mana of the chosen color."
 *
 * The white member of the cycle: a two-colour land whose second colour is fixed **as it enters** rather
 * than at each activation. The distinction is the card — a Gate that chose blue is a WU land for the rest
 * of the game and can never be tapped for black — and it is why the choice is stored on the object
 * ([dev.mtgplay.core.state.GameObject.chosenColor]) instead of being an option list on the ability.
 *
 * **"Other than white" is an exclusion on the *enumeration*, not a convention.** Offering white would be
 * an enumerated-but-illegal action (ADR-005): the Gate would then be a legal way to tap for `{W}{W}`,
 * which is precisely the line the printed restriction exists to forbid.
 */
val citadelGate: CardDefinition = gate(name = "Citadel Gate", own = Color.WHITE)

/**
 * Cliffgate — Land — Gate. "This land enters tapped. As this land enters, choose a color other than red.
 * {T}: Add {R} or one mana of the chosen color." The red [citadelGate].
 */
val cliffgate: CardDefinition = gate(name = "Cliffgate", own = Color.RED)

/**
 * Manor Gate — Land — Gate. "This land enters tapped. As this land enters, choose a color other than
 * green. {T}: Add {G} or one mana of the chosen color." The green [citadelGate].
 */
val manorGate: CardDefinition = gate(name = "Manor Gate", own = Color.GREEN)

/**
 * One member of the Gate cycle: a Gate that enters tapped (CR 614.1c), chooses a colour other than [own]
 * as it enters (CR 614.12), and has the single printed mana ability "{T}: Add [own] or one mana of the
 * chosen color" (CR 605.1a).
 *
 * **One ability offering a choice, not two abilities** — the shape the Bridges take for "{T}: Add {B} or
 * {R}", and the printed one. Its option list is the fixed [own] colour; the chosen colour joins it in the
 * production profile `mtg-rules` derives per object, which is what keeps the card's declaration a
 * property of the *card* while the second colour stays a property of the *permanent*.
 */
private fun gate(
    name: String,
    own: Color,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(GATE),
                powerToughness = null,
            )

        override val entersTapped = EntersTapped.Always

        // CR 614.12: "choose a color other than <own>" — the exclusion is on the option list.
        override val asEntersColorChoice = AsEntersColorChoice(excluding = own)

        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(own.manaType()),
                    includesChosenColor = true,
                ),
            )
    }
