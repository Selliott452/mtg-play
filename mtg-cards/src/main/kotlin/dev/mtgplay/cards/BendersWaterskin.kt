package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Bender's Waterskin (`W8-A`) — the card docs/design/mana-payment.md §11.7 filed as needing "**nothing**
 * from this framework": its `{T}`: Add one mana of any color has been expressible since P2.1, and the
 * single thing standing in its way was the printed line above it.
 */

/** The five colours an "add one mana of any color" ability offers, in WUBRG order (CR 105.1). */
private val ANY_COLOR =
    persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)

/**
 * Bender's Waterskin — `{3}` Artifact. "Untap this artifact during each other player's untap step.
 * {T}: Add one mana of any color."
 *
 * **The whole card is the first line**, and it is the pool's first *rules-modifying* static ability
 * (CR 613.11): it changes which permanents the CR 502.2 untap-step turn-based action untaps, rather than
 * changing any characteristic of any object. There is no CR 613 layer for that, which is why it is
 * declared as [dev.mtgplay.core.definition.CardDefinition.untapsInEachOtherPlayersUntapStep] and read in
 * the untap step itself — the same shape [dev.mtgplay.core.definition.CardDefinition.entersTapped] takes
 * for the CR 614.1c self-replacement, and for the same reason.
 *
 * What it buys is a real and otherwise-absent line of play: a mana source that is available on **both**
 * turns of a round. Tapping it for `{U}` on your own turn and having it untapped again for a
 * counterspell on the opponent's is exactly what the card does, and encoding it as a plain mana rock
 * would silently delete half of it.
 *
 * The mana half is unremarkable — an "add one mana of any color" filter, [ANY_COLOR] with the default
 * `{T}` cost — and it is the shape ColorlessArtifacts.kt once recorded as absent. It has not been absent
 * since `FW-MANA`; [barrelsOfBlastingJelly] and [giantsBoulder] already print it.
 */
val bendersWaterskin: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Bender's Waterskin",
                manaCost = ManaCost.parse("{3}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: an artifact spell with no instructions of its own resolves by entering the battlefield.
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 613.11: the printed first line, read by the CR 502.2 untap-step turn-based action.
        override val untapsInEachOtherPlayersUntapStep = true

        override val manaAbilities = persistentListOf(ManaAbility(options = ANY_COLOR))
    }
