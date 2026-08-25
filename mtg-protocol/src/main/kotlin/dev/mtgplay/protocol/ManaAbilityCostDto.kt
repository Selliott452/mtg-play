package dev.mtgplay.protocol

import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.decision.ProductionAlternative
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The wire form of a mana ability's *cost*, and of the production alternative that carries it
 * (`FW-MANACOST`, CR 605.1a). Split from PaymentPlanDto.kt so each file stays within its function
 * budget; the two halves are one concept — a payment plan names alternatives, and an alternative
 * names its cost.
 */

/**
 * Wire form of one [dev.mtgplay.rules.decision.ProductionAlternative] (CR 605.1a): one way a member of
 * a source class may be activated for mana.
 *
 * @property cost the components one activation pays, in printed order; never empty.
 * @property produced the mana the ability adds, as a multiset — `["GREEN"]` for a Forest,
 *   `["COLORLESS", "COLORLESS", "COLORLESS"]` for an Urza's Tower with Tron assembled. Never empty.
 * @property oncePerTurn whether taking this alternative spends the source's CR 602.5b
 *   "Activate only once each turn" allowance.
 */
@Serializable
data class ProductionAlternativeDto(
    val cost: List<ManaAbilityCostDto>,
    val produced: List<ManaTypeDto>,
    val oncePerTurn: Boolean,
)

/**
 * Wire form of one [ManaAbilityCost] component (CR 602.1, CR 605.1a). Sealed, so a peer that meets an
 * unknown discriminator fails loudly rather than silently treating a costed ability as free.
 */
@Serializable
sealed interface ManaAbilityCostDto {
    /**
     * A mana component (CR 118), carried as its Scryfall brace-syntax string, which
     * [dev.mtgplay.core.mana.ManaCost.render] and `parse` round-trip exactly.
     */
    @Serializable
    @SerialName("mana")
    data class Mana(
        val cost: String,
    ) : ManaAbilityCostDto

    /** Tap the source permanent (the `{T}` symbol, CR 602.2a). */
    @Serializable
    @SerialName("tap_self")
    data object TapSelf : ManaAbilityCostDto

    /** Sacrifice the source permanent (CR 701.17) — an Eldrazi Spawn's "Sacrifice this token". */
    @Serializable
    @SerialName("sacrifice_self")
    data object SacrificeSelf : ManaAbilityCostDto

    /** Tap another untapped creature you control (CR 602.1) — Saruli Caretaker's. */
    @Serializable
    @SerialName("tap_another_creature")
    data object TapAnotherCreature : ManaAbilityCostDto

    /**
     * Put a counter on the source permanent (CR 122.1) — Wall of Roots' `-0/-1`.
     *
     * @property counter the counter placed, in the same wire shape a permanent's counters take; its
     *   `count` is how many this cost places (always one in the gauntlet pool), not how many the
     *   permanent already carries.
     */
    @Serializable
    @SerialName("put_counter_on_self")
    data class PutCounterOnSelf(
        val counter: CounterDto,
    ) : ManaAbilityCostDto
}

/** [ProductionAlternative] to its wire form. */
fun ProductionAlternative.toDto(): ProductionAlternativeDto =
    ProductionAlternativeDto(cost.map { it.toDto() }, produced.map { it.toDto() }, oncePerTurn)

/** [ProductionAlternativeDto] back to the engine value. */
fun ProductionAlternativeDto.toDomain(): ProductionAlternative =
    ProductionAlternative(cost.map { it.toDomain() }, produced.map { it.toDomain() }, oncePerTurn)

/** [ManaAbilityCost] to its wire form. */
fun ManaAbilityCost.toDto(): ManaAbilityCostDto =
    when (this) {
        is ManaAbilityCost.Mana -> ManaAbilityCostDto.Mana(cost.render())
        ManaAbilityCost.TapSelf -> ManaAbilityCostDto.TapSelf
        ManaAbilityCost.SacrificeSelf -> ManaAbilityCostDto.SacrificeSelf
        ManaAbilityCost.TapAnotherCreature -> ManaAbilityCostDto.TapAnotherCreature
        is ManaAbilityCost.PutCounterOnSelf ->
            ManaAbilityCostDto.PutCounterOnSelf(persistentMapOf(counter to 1).toDto().single())
    }

/** [ManaAbilityCostDto] back to the engine value. */
fun ManaAbilityCostDto.toDomain(): ManaAbilityCost =
    when (this) {
        is ManaAbilityCostDto.Mana -> ManaAbilityCost.Mana(ManaCost.parse(cost))
        ManaAbilityCostDto.TapSelf -> ManaAbilityCost.TapSelf
        ManaAbilityCostDto.SacrificeSelf -> ManaAbilityCost.SacrificeSelf
        ManaAbilityCostDto.TapAnotherCreature -> ManaAbilityCost.TapAnotherCreature
        is ManaAbilityCostDto.PutCounterOnSelf ->
            ManaAbilityCost.PutCounterOnSelf(listOf(counter).toDomain().keys.single())
    }
