package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one enumerated [PaymentPlan] (CR 601.2g–h): a payment per expanded cost symbol.
 *
 * @property payments one payment per cost symbol, in printed order; empty for a `{0}` cost.
 */
@Serializable
data class PaymentPlanDto(
    val payments: List<SymbolPaymentDto>,
)

/** Wire form of one [SymbolPayment] — one mana, or a Phyrexian symbol's 2-life alternative. */
@Serializable
sealed interface SymbolPaymentDto {
    /** Pay the symbol with one [mana] from [source] (CR 601.2h). */
    @Serializable
    @SerialName("with_mana")
    data class WithMana(
        val mana: ManaTypeDto,
        val source: ManaSourceChoiceDto,
    ) : SymbolPaymentDto

    /** Pay a Phyrexian symbol's 2-life alternative (CR 107.4). */
    @Serializable
    @SerialName("with_two_life")
    data object WithTwoLife : SymbolPaymentDto
}

/** Wire form of a [ManaSourceChoice] — mana from the pool, or by tapping a source class. */
@Serializable
sealed interface ManaSourceChoiceDto {
    /** Mana already in the pool (CR 106.4). */
    @Serializable
    @SerialName("from_pool")
    data object FromPool : ManaSourceChoiceDto

    /** Tap one member of [sourceClass] for its mana (CR 605.3). */
    @Serializable
    @SerialName("by_tapping")
    data class ByTapping(
        val sourceClass: SourceClassKeyDto,
    ) : ManaSourceChoiceDto
}

/**
 * Wire form of a [SourceClassKey]: the identity of one class of payment-equivalent mana sources.
 *
 * @property card the printed card every member shares.
 * @property profile the canonical mana a member's tap adds, in WUBRG-then-colorless order.
 * @property bonus extra mana a triggered mana ability adds (CR 605.1b); empty for an ordinary source.
 * @property viaSacrifice whether a member is sacrificed rather than tapped (CR 605.1a).
 */
@Serializable
data class SourceClassKeyDto(
    val card: String,
    val profile: List<ManaTypeDto>,
    val bonus: List<ManaTypeDto>,
    val viaSacrifice: Boolean,
)

/** [PaymentPlan] to its wire form. */
fun PaymentPlan.toDto(): PaymentPlanDto = PaymentPlanDto(payments.map { it.toDto() })

/** [PaymentPlanDto] back to the engine value. */
fun PaymentPlanDto.toDomain(): PaymentPlan = PaymentPlan(payments.map { it.toDomain() })

/** [SymbolPayment] to its wire form. */
fun SymbolPayment.toDto(): SymbolPaymentDto =
    when (this) {
        is SymbolPayment.WithMana -> SymbolPaymentDto.WithMana(mana.toDto(), source.toDto())
        SymbolPayment.WithTwoLife -> SymbolPaymentDto.WithTwoLife
    }

/** [SymbolPaymentDto] back to the engine value. */
fun SymbolPaymentDto.toDomain(): SymbolPayment =
    when (this) {
        is SymbolPaymentDto.WithMana -> SymbolPayment.WithMana(mana.toDomain(), source.toDomain())
        SymbolPaymentDto.WithTwoLife -> SymbolPayment.WithTwoLife
    }

/** [ManaSourceChoice] to its wire form. */
fun ManaSourceChoice.toDto(): ManaSourceChoiceDto =
    when (this) {
        ManaSourceChoice.FromPool -> ManaSourceChoiceDto.FromPool
        is ManaSourceChoice.ByTapping -> ManaSourceChoiceDto.ByTapping(sourceClass.toDto())
    }

/** [ManaSourceChoiceDto] back to the engine value. */
fun ManaSourceChoiceDto.toDomain(): ManaSourceChoice =
    when (this) {
        ManaSourceChoiceDto.FromPool -> ManaSourceChoice.FromPool
        is ManaSourceChoiceDto.ByTapping -> ManaSourceChoice.ByTapping(sourceClass.toDomain())
    }

/** [SourceClassKey] to its wire form. */
fun SourceClassKey.toDto(): SourceClassKeyDto =
    SourceClassKeyDto(
        card = card.name,
        profile = profile.map { it.toDto() },
        bonus = bonus.map { it.toDto() },
        viaSacrifice = viaSacrifice,
    )

/** [SourceClassKeyDto] back to the engine value. */
fun SourceClassKeyDto.toDomain(): SourceClassKey =
    SourceClassKey(
        card = CardRef(card),
        profile = profile.map { it.toDomain() },
        bonus = bonus.map { it.toDomain() },
        viaSacrifice = viaSacrifice,
    )
