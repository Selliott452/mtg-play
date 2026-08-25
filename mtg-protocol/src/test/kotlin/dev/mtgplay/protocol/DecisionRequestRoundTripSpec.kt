package dev.mtgplay.protocol

import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.DecisionRequestKind
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.decision.ProductionAlternative
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.kindOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The schema round-trip (ADR-008): every one of the 28 [DecisionRequest] kinds, and every
 * [Decision] shape, survives engine value -> DTO -> JSON -> DTO -> engine value unchanged, through
 * the strict [ProtocolJson] codec. The `allRequests` fixture is asserted to cover every kind, so the
 * exhaustive mapping is exercised end to end.
 */
class DecisionRequestRoundTripSpec :
    StringSpec({
        "ADR-008: every DecisionRequest kind is represented in the round-trip fixture" {
            allRequests.map { kindOf(it) } shouldContainExactlyInAnyOrder DecisionRequestKind.entries.toList()
        }

        "ADR-008: every DecisionRequest kind round-trips engine -> DTO -> JSON -> DTO -> engine" {
            allRequests.forEach { request ->
                val json = ProtocolJson.encodeToString(request.toDto())
                ProtocolJson.decodeFromString<DecisionRequestDto>(json).toDomain() shouldBe request
            }
        }

        "ADR-007: every DecisionRequestKind maps name-identically to its wire form and back" {
            DecisionRequestKind.entries.forEach { kind -> kind.toDto().toDomain() shouldBe kind }
        }

        "ADR-005: both Decision shapes round-trip through the codec" {
            val decisions =
                listOf(
                    Decision.SingleSelect(DecisionRequestId(PlayerId(0), 2), 1),
                    Decision.MultiSelect(DecisionRequestId(PlayerId(1), 5), listOf(2, 0, 1)),
                )
            decisions.forEach { decision ->
                val json = ProtocolJson.encodeToString(decision.toDto())
                ProtocolJson.decodeFromString<DecisionDto>(json).toDomain() shouldBe decision
            }
        }
    })

private val ID = DecisionRequestId(PlayerId(0), 3)

private fun cardOption(
    id: Long,
    name: String,
) = DecisionRequest.ChooseDiscards.Option(ObjectId(id), CardRef(name))

/**
 * A priority window carrying every [PriorityOption] kind and, across its cast options, every
 * [CastingPermission] member — so the round-trip exercises the whole option/permission surface.
 */
private val richPriorityWindow: DecisionRequest.ChooseAction =
    DecisionRequest.ChooseAction(
        ID,
        listOf(
            PriorityOption.Pass,
            PriorityOption.CastSpell(ObjectId(1), CardRef("Lightning Bolt")),
            PriorityOption.CastSpell(
                ObjectId(2),
                CardRef("Sneaky Snacker"),
                CastSource.EXILE,
                CastingPermission.Madness(ManaCost.parse("{1}{R}")),
            ),
            PriorityOption.CastSpell(
                ObjectId(3),
                CardRef("Lava Dart"),
                CastSource.GRAVEYARD,
                CastingPermission.Flashback(ManaCost.parse("{0}"), SacrificeRequirement(1, Subtype("Mountain"))),
            ),
            PriorityOption.CastSpell(
                ObjectId(4),
                CardRef("Sentinel's Eyes"),
                CastSource.GRAVEYARD,
                CastingPermission.Escape(ManaCost.parse("{1}{G}"), exileOthers = 2),
            ),
            PriorityOption.CastSpell(
                ObjectId(5),
                CardRef("Highway Robbery"),
                CastSource.EXILE,
                CastingPermission.Plot(ManaCost.parse("{1}{R}")),
            ),
            PriorityOption.CastSpell(
                ObjectId(6),
                CardRef("Fireblast"),
                CastSource.HAND,
                CastingPermission.AlternativeCost(ManaCost.parse("{0}"), SacrificeRequirement(2, Subtype("Mountain"))),
            ),
            PriorityOption.PlayLand(ObjectId(7), CardRef("Mountain")),
            PriorityOption.PlotCard(ObjectId(8), CardRef("Highway Robbery")),
            PriorityOption.ActivateAbility(ObjectId(9), CardRef("Blood"), 0, AbilityZoneScope.Battlefield),
            PriorityOption.ActivateAbility(ObjectId(10), CardRef("Ash Barrens"), 0, AbilityZoneScope.Hand),
        ),
    )

/**
 * A payment window exercising every [ManaActivation]/[SymbolPayment] shape, plus the `FW-COST`
 * determined-cost field (CR 601.2f). The cost is deliberately **not** Lightning Bolt's printed `{R}`:
 * a round trip that carried the printed cost would still pass if `toDto` derived the field from the
 * card instead of transporting it, and the whole point of the field is that the two differ.
 */
private val richPaymentWindow: DecisionRequest.ChoosePaymentPlan =
    DecisionRequest.ChoosePaymentPlan(
        ID,
        ObjectId(1),
        CardRef("Lightning Bolt"),
        ManaCost.parse("{2}{R}"),
        listOf(
            PaymentPlan(
                listOf(
                    ManaActivation(
                        SourceClassKey(
                            CardRef("Forest"),
                            listOf(ProductionAlternative.tapping(ManaType.GREEN)),
                            listOf(ManaType.GREEN),
                        ),
                        ProductionAlternative.tapping(ManaType.GREEN),
                    ),
                    // CR 605.2: a multi-mana production alternative on the wire — the FW-MANA shape.
                    ManaActivation(
                        SourceClassKey(
                            CardRef("Urza's Tower"),
                            listOf(ProductionAlternative.tapping(*Array(3) { ManaType.COLORLESS })),
                        ),
                        ProductionAlternative.tapping(*Array(3) { ManaType.COLORLESS }),
                    ),
                    ManaActivation(
                        SourceClassKey(
                            CardRef("Eldrazi Spawn"),
                            listOf(ProductionAlternative.sacrificing(ManaType.COLORLESS)),
                        ),
                        ProductionAlternative.sacrificing(ManaType.COLORLESS),
                    ),
                ),
                listOf(
                    SymbolPayment.WithMana(ManaType.RED),
                    SymbolPayment.WithMana(ManaType.GREEN),
                    SymbolPayment.WithTwoLife,
                ),
            ),
        ),
    )

/**
 * A resolving counter's unless-pay window (CR 118.3a) carrying both answers: the decline at index 0 and
 * a payment plan after it, so the fused shape's sealed option hierarchy round-trips in both arms.
 */
private val richCounterPaymentWindow: DecisionRequest.ChooseCounterPayment =
    DecisionRequest.ChooseCounterPayment(
        ID,
        CardRef("Lightning Bolt"),
        ManaCost.parse("{1}"),
        listOf(
            DecisionRequest.ChooseCounterPayment.Option.Decline,
            DecisionRequest.ChooseCounterPayment.Option.Pay(
                PaymentPlan(
                    listOf(
                        ManaActivation(
                            SourceClassKey(
                                CardRef("Island"),
                                listOf(ProductionAlternative.tapping(ManaType.BLUE)),
                            ),
                            ProductionAlternative.tapping(ManaType.BLUE),
                        ),
                    ),
                    listOf(SymbolPayment.WithMana(ManaType.BLUE)),
                ),
            ),
        ),
    )

/** One representative instance of every [DecisionRequest] kind. */
private val allRequests: List<DecisionRequest> =
    listOf(
        richPriorityWindow,
        DecisionRequest.ChooseDiscards(ID, listOf(cardOption(1, "Mountain"), cardOption(2, "Bog")), count = 1),
        DecisionRequest.ChooseTargets(
            ID,
            ObjectId(1),
            CardRef("Lightning Bolt"),
            listOf(
                Target.Player(PlayerId(1)),
                Target.Permanent(ObjectId(9)),
                Target.SpellOnStack(ObjectId(11)),
                Target.CardInGraveyard(ObjectId(13)),
            ),
        ),
        richPaymentWindow,
        richCounterPaymentWindow,
        DecisionRequest.DeclareAttackers(
            ID,
            listOf(DecisionRequest.DeclareAttackers.Option(ObjectId(9), CardRef("Grizzly Bears"), PlayerId(1))),
        ),
        DecisionRequest.DeclareBlockers(
            ID,
            listOf(DecisionRequest.DeclareBlockers.Option(ObjectId(9), CardRef("Wall"), ObjectId(8), CardRef("Bears"))),
        ),
        DecisionRequest.OrderBlockers(
            ID,
            ObjectId(8),
            listOf(
                DecisionRequest.OrderBlockers.Option(ObjectId(9), CardRef("Wall")),
                DecisionRequest.OrderBlockers.Option(ObjectId(10), CardRef("Bears")),
            ),
        ),
        DecisionRequest.AssignTrampleDamage(ID, ObjectId(8), CardRef("Rancored Bears"), PlayerId(1), listOf(0, 1, 2)),
        DecisionRequest.OrderTriggers(
            ID,
            listOf(
                DecisionRequest.OrderTriggers.Option(CardRef("Guttersnipe"), "deal 2 damage"),
                DecisionRequest.OrderTriggers.Option(CardRef("Rancor"), "return to hand"),
            ),
        ),
        DecisionRequest.ChooseYesNo(ID, "cast for madness", ObjectId(2), CardRef("Sneaky Snacker")),
        DecisionRequest.ChooseCardsToExile(
            ID,
            ObjectId(4),
            CardRef("Sentinel's Eyes"),
            listOf(
                DecisionRequest.ChooseCardsToExile.Option(ObjectId(1), CardRef("Bog")),
                DecisionRequest.ChooseCardsToExile.Option(ObjectId(2), CardRef("Loam")),
            ),
            count = 2,
        ),
        DecisionRequest.ChooseSacrifices(
            ID,
            ObjectId(6),
            CardRef("Fireblast"),
            listOf(
                DecisionRequest.ChooseSacrifices.Option(ObjectId(11), CardRef("Mountain")),
                DecisionRequest.ChooseSacrifices.Option(ObjectId(12), CardRef("Mountain")),
            ),
            count = 2,
        ),
        DecisionRequest.ChooseSacrificesForCost(
            ID,
            ObjectId(7),
            CardRef("Eviscerator's Insight"),
            listOf(
                DecisionRequest.ChooseSacrificesForCost.Option(ObjectId(13), CardRef("Ichor Wellspring")),
                DecisionRequest.ChooseSacrificesForCost.Option(ObjectId(14), CardRef("Gladecover Scout")),
            ),
            count = 1,
        ),
        DecisionRequest.ChooseAbilitySacrifice(
            ID,
            ObjectId(8),
            CardRef("Makeshift Munitions"),
            listOf(DecisionRequest.ChooseAbilitySacrifice.Option(ObjectId(15), CardRef("Ichor Wellspring"))),
            count = 1,
        ),
        DecisionRequest.ChooseCardsToDiscardForCost(
            ID,
            ObjectId(1),
            CardRef("Grab the Prize"),
            listOf(DecisionRequest.ChooseCardsToDiscardForCost.Option(ObjectId(2), CardRef("Bog"))),
            count = 1,
        ),
        DecisionRequest.ChooseMulligan(ID, mulligansTaken = 1),
        DecisionRequest.ChooseCardsToBottom(
            ID,
            listOf(
                DecisionRequest.ChooseCardsToBottom.Option(ObjectId(1), CardRef("Mountain")),
                DecisionRequest.ChooseCardsToBottom.Option(ObjectId(2), CardRef("Bog")),
            ),
            count = 1,
        ),
        DecisionRequest.ChooseAbilityDiscard(
            ID,
            ObjectId(9),
            CardRef("Blood"),
            listOf(DecisionRequest.ChooseAbilityDiscard.Option(ObjectId(1), CardRef("Mountain"))),
            count = 1,
        ),
        DecisionRequest.ChooseColor(ID, ObjectId(9), CardRef("Utopia Sprawl"), listOf(Color.GREEN, Color.RED)),
        DecisionRequest.ChooseOptionalDiscard(
            ID,
            listOf(DecisionRequest.ChooseOptionalDiscard.Option(ObjectId(1), CardRef("Mountain"))),
            count = 1,
        ),
        DecisionRequest.ChooseFromRevealed(
            ID,
            listOf(DecisionRequest.ChooseFromRevealed.Option(ObjectId(1), CardRef("Grizzly Bears"))),
        ),
        DecisionRequest.ChooseReplacement(
            ID,
            listOf(
                DecisionRequest.ChooseReplacement.Option("exile A"),
                DecisionRequest.ChooseReplacement.Option("exile B"),
            ),
        ),
        DecisionRequest.ChooseCostMode(
            ID,
            "discard or sacrifice",
            listOf(OptionalCostMode.DiscardCard, OptionalCostMode.SacrificeLand),
        ),
        DecisionRequest.ChooseOptionalCostObject(
            ID,
            listOf(DecisionRequest.ChooseOptionalCostObject.Option(ObjectId(1), CardRef("Mountain"))),
        ),
        DecisionRequest.ChooseResolutionDiscards(
            ID,
            listOf(
                DecisionRequest.ChooseResolutionDiscards.Option(ObjectId(1), CardRef("Mountain")),
                DecisionRequest.ChooseResolutionDiscards.Option(ObjectId(2), CardRef("Bog")),
            ),
            count = 2,
        ),
        DecisionRequest.ChooseFromLibrary(
            ID,
            listOf(DecisionRequest.ChooseFromLibrary.Option(ObjectId(1), CardRef("Mountain"))),
        ),
        // CR 701.17a: a scry 2's six arrangements over a two-card pool, the framework's smallest real space.
        DecisionRequest.ChooseLibraryArrangement(
            ID,
            prompt = "Scry 2",
            pool =
                listOf(
                    DecisionRequest.ChooseLibraryArrangement.PoolCard(ObjectId(1), CardRef("Mountain")),
                    DecisionRequest.ChooseLibraryArrangement.PoolCard(ObjectId(2), CardRef("Bog")),
                ),
            options =
                listOf(
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), listOf(0, 1), emptyList()),
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), listOf(1, 0), emptyList()),
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), listOf(1), listOf(0)),
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), listOf(0), listOf(1)),
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), emptyList(), listOf(0, 1)),
                    DecisionRequest.ChooseLibraryArrangement.Option(emptyList(), emptyList(), listOf(1, 0)),
                ),
        ),
    )
