package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one enumerated [PaymentPlan] (CR 601.2g–h): the mana abilities to activate, then a
 * payment per expanded cost symbol.
 *
 * @property activations the mana abilities activated before paying (CR 601.2g); empty when the
 *   pool already covers the cost. One activation may pay several symbols.
 * @property payments one payment per cost symbol, in printed order; empty for a `{0}` cost.
 */
@Serializable
data class PaymentPlanDto(
    val activations: List<ManaActivationDto>,
    val payments: List<SymbolPaymentDto>,
)

/**
 * Wire form of one [ManaActivation]: activate a member of [sourceClass] for the mana of the chosen
 * production alternative [produced] (CR 601.2g, CR 605.1a).
 *
 * @property produced the alternative the activator chose, as a multiset — `["GREEN"]` for a Forest,
 *   `["COLORLESS", "COLORLESS", "COLORLESS"]` for an Urza's Tower with Tron assembled. One of
 *   [SourceClassKeyDto.profile]'s entries; never empty.
 */
@Serializable
data class ManaActivationDto(
    val sourceClass: SourceClassKeyDto,
    val produced: List<ManaTypeDto>,
)

/** Wire form of one [SymbolPayment] — one mana, or a Phyrexian symbol's 2-life alternative. */
@Serializable
sealed interface SymbolPaymentDto {
    /** Pay the symbol with one [mana] from the pool (CR 601.2h). */
    @Serializable
    @SerialName("with_mana")
    data class WithMana(
        val mana: ManaTypeDto,
    ) : SymbolPaymentDto

    /** Pay a Phyrexian symbol's 2-life alternative (CR 107.4). */
    @Serializable
    @SerialName("with_two_life")
    data object WithTwoLife : SymbolPaymentDto
}

/**
 * Wire form of a [SourceClassKey]: the identity of one class of payment-equivalent mana sources.
 *
 * @property card the printed card every member shares.
 * @property profile the production **alternatives** one activation of a member may choose between,
 *   each a multiset of mana types in WUBRG-then-colorless order (CR 105.1); never empty, and no
 *   alternative is empty. Computed from the board when the plan was enumerated, so an Urza's Tower
 *   with Tron assembled reports `[["COLORLESS","COLORLESS","COLORLESS"]]` and one without reports
 *   `[["COLORLESS"]]` (CR 605.2).
 * @property bonus extra mana a triggered mana ability adds on activation (CR 605.1b); empty for an
 *   ordinary source. Part of an activation's yield, so it is spendable by the plan that produced it.
 * @property viaSacrifice whether a member is sacrificed rather than tapped (CR 605.1a).
 */
@Serializable
data class SourceClassKeyDto(
    val card: String,
    val profile: List<List<ManaTypeDto>>,
    val bonus: List<ManaTypeDto>,
    val viaSacrifice: Boolean,
)

/** [PaymentPlan] to its wire form. */
fun PaymentPlan.toDto(): PaymentPlanDto = PaymentPlanDto(activations.map { it.toDto() }, payments.map { it.toDto() })

/** [PaymentPlanDto] back to the engine value. */
fun PaymentPlanDto.toDomain(): PaymentPlan =
    PaymentPlan(activations.map { it.toDomain() }, payments.map { it.toDomain() })

/** [ManaActivation] to its wire form. */
fun ManaActivation.toDto(): ManaActivationDto = ManaActivationDto(sourceClass.toDto(), produced.map { it.toDto() })

/** [ManaActivationDto] back to the engine value. */
fun ManaActivationDto.toDomain(): ManaActivation =
    ManaActivation(sourceClass.toDomain(), produced.map { it.toDomain() })

/** [SymbolPayment] to its wire form. */
fun SymbolPayment.toDto(): SymbolPaymentDto =
    when (this) {
        is SymbolPayment.WithMana -> SymbolPaymentDto.WithMana(mana.toDto())
        SymbolPayment.WithTwoLife -> SymbolPaymentDto.WithTwoLife
    }

/** [SymbolPaymentDto] back to the engine value. */
fun SymbolPaymentDto.toDomain(): SymbolPayment =
    when (this) {
        is SymbolPaymentDto.WithMana -> SymbolPayment.WithMana(mana.toDomain())
        SymbolPaymentDto.WithTwoLife -> SymbolPayment.WithTwoLife
    }

/** [SourceClassKey] to its wire form. */
fun SourceClassKey.toDto(): SourceClassKeyDto =
    SourceClassKeyDto(
        card = card.name,
        profile = profile.map { alternative -> alternative.map { it.toDto() } },
        bonus = bonus.map { it.toDto() },
        viaSacrifice = viaSacrifice,
    )

/** [SourceClassKeyDto] back to the engine value. */
fun SourceClassKeyDto.toDomain(): SourceClassKey =
    SourceClassKey(
        card = CardRef(card),
        profile = profile.map { alternative -> alternative.map { it.toDomain() } },
        bonus = bonus.map { it.toDomain() },
        viaSacrifice = viaSacrifice,
    )
