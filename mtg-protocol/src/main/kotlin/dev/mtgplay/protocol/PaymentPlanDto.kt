package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.rules.decision.DecisionRequest
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
 * Wire form of one [ManaActivation]: activate a member of [sourceClass] taking the production
 * [alternative], paying [costPayment] toward that alternative's own activation cost (CR 601.2g,
 * CR 605.1a).
 *
 * @property alternative the alternative the activator chose — one of [SourceClassKeyDto.profile]'s
 *   entries, naming both what the activation costs and what it adds.
 * @property costPayment one mana per expanded symbol of the alternative's own mana cost, in printed
 *   order; empty for a free ability, which is every ability on a board with no costed mana source.
 */
@Serializable
data class ManaActivationDto(
    val sourceClass: SourceClassKeyDto,
    val alternative: ProductionAlternativeDto,
    val costPayment: List<ManaTypeDto>,
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
 *   each naming its cost and the multiset it adds; never empty. Computed from the board when the plan
 *   was enumerated, so an Urza's Tower with Tron assembled reports a single three-colorless
 *   alternative and one without reports a single one-colorless alternative (CR 605.2), and a source
 *   that has spent its CR 602.5b once-each-turn activation reports no class at all.
 * @property bonus extra mana a triggered mana ability adds on activation (CR 605.1b); empty for an
 *   ordinary source. Part of an activation's yield, so it is spendable by the plan that produced it.
 */
@Serializable
data class SourceClassKeyDto(
    val card: String,
    val profile: List<ProductionAlternativeDto>,
    val bonus: List<ManaTypeDto>,
)

/**
 * Wire form of one [dev.mtgplay.rules.decision.DecisionRequest.ChooseCounterPayment.Option] (CR 118.3a):
 * decline, or pay by a named plan. Sealed so the fused request's two answers stay distinguishable on the
 * wire rather than being encoded as "index 0 means no".
 */
@Serializable
sealed interface CounterPaymentOptionDto {
    /** Do not pay; the targeted spell is countered (CR 701.5a). Always index 0. */
    @Serializable
    @SerialName("decline")
    data object Decline : CounterPaymentOptionDto

    /** Pay the full cost by [plan] (CR 118.3a); the targeted spell is saved. */
    @Serializable
    @SerialName("pay")
    data class Pay(
        val plan: PaymentPlanDto,
    ) : CounterPaymentOptionDto
}

/** [PaymentPlan] to its wire form. */
fun PaymentPlan.toDto(): PaymentPlanDto = PaymentPlanDto(activations.map { it.toDto() }, payments.map { it.toDto() })

/** [PaymentPlanDto] back to the engine value. */
fun PaymentPlanDto.toDomain(): PaymentPlan =
    PaymentPlan(activations.map { it.toDomain() }, payments.map { it.toDomain() })

/** A counter-payment option to its wire form. */
fun DecisionRequest.ChooseCounterPayment.Option.toDto(): CounterPaymentOptionDto =
    when (this) {
        DecisionRequest.ChooseCounterPayment.Option.Decline -> CounterPaymentOptionDto.Decline
        is DecisionRequest.ChooseCounterPayment.Option.Pay -> CounterPaymentOptionDto.Pay(plan.toDto())
    }

/** A counter-payment option back to the engine value. */
fun CounterPaymentOptionDto.toDomain(): DecisionRequest.ChooseCounterPayment.Option =
    when (this) {
        CounterPaymentOptionDto.Decline -> DecisionRequest.ChooseCounterPayment.Option.Decline
        is CounterPaymentOptionDto.Pay -> DecisionRequest.ChooseCounterPayment.Option.Pay(plan.toDomain())
    }

/** [ManaActivation] to its wire form. */
fun ManaActivation.toDto(): ManaActivationDto =
    ManaActivationDto(sourceClass.toDto(), alternative.toDto(), costPayment.map { it.toDto() })

/** [ManaActivationDto] back to the engine value. */
fun ManaActivationDto.toDomain(): ManaActivation =
    ManaActivation(sourceClass.toDomain(), alternative.toDomain(), costPayment.map { it.toDomain() })

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
        profile = profile.map { it.toDto() },
        bonus = bonus.map { it.toDto() },
    )

/** [SourceClassKeyDto] back to the engine value. */
fun SourceClassKeyDto.toDomain(): SourceClassKey =
    SourceClassKey(
        card = CardRef(card),
        profile = profile.map { it.toDomain() },
        bonus = bonus.map { it.toDomain() },
    )
