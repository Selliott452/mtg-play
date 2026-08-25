package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.returnToOwnersHand
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's graveyard-targeting cards (docs/design/graveyard-targeting.md): the spells and
 * abilities whose target is a **card in a graveyard** (CR 115.1, CR 404) rather than a permanent, a
 * player, or a spell on the stack.
 *
 * This packet (`FW-ZONETGT`) added the framework the family needs: [Target.CardInGraveyard], the
 * [TargetSpec.CardInGraveyard] spec with its [GraveyardCardRestriction] noun and [GraveyardScope]
 * possessive, the `Targets.kt` enumeration, and the ADR-007 ruling that a graveyard is a public zone so
 * the option list needs no per-seat filtering. The two cards here are the family members that compose
 * only published primitives on top of it — everything they do beyond targeting is
 * [returnToOwnersHand] and [gainLife], both of which already existed.
 *
 * **Six of the eight cards this packet was scoped to are deliberately absent**, each blocked on a
 * framework this packet does not own; an approximation of any of them would be a plausible-looking
 * wrong card (PLAN.md §7). docs/design/graveyard-targeting.md §6 gives each in full, and in short:
 * - **Faerie Macabre**, **Rooftop Percher**, **Blood Fountain**, **Call Damage Control** all print
 *   "up to two target …", which is `FW-MULTITGT`: [TargetSpec] and
 *   [dev.mtgplay.rules.decision.DecisionRequest.ChooseTargets] are single-target by construction.
 * - **Dread Return**'s flashback cost is "Sacrifice three creatures", and
 *   [dev.mtgplay.core.definition.SacrificeRequirement] predicates on a printed *subtype*, not a card
 *   type.
 * - **Mortuary Mire** is a land whose enters-the-battlefield trigger would never fire: `executePlayLand`
 *   does not call `detectEnterBattlefieldTriggers` (triage T18).
 */

/** The life Pulse of Murasa's controller gains (CR 119.3). */
const val PULSE_OF_MURASA_LIFEGAIN: Int = 6

/**
 * Archaeomancer — `{2}{U}{U}` Creature — Human Wizard 1/2. "When this creature enters, return target
 * instant or sorcery card from your graveyard to your hand."
 *
 * The first card whose *ability* targets a card in a graveyard: the target is chosen as the trigger is
 * put on the stack (CR 603.3d) and re-checked on resolution (CR 608.2b), exactly as Lotleth Giant's is.
 * The creature spell itself is untargeted and sorcery-speed (CR 302.1); the [TargetSpec] sits on the
 * [TriggeredAbility], not on the card.
 *
 * **With no instant or sorcery card in the controller's graveyard the trigger still goes on the stack
 * and then does nothing** (CR 603.3d, CR 608.2b): the enumeration is empty, so no `ChooseTargets`
 * request is surfaced (ADR-005), the ability is placed carrying no targets, and `allTargetsIllegal` is
 * vacuously true. That is the correct reading of a mandatory targeted trigger with no legal target, and
 * it is the common case on an empty board.
 *
 * The graveyard is the controller's own ([GraveyardScope.YOURS]), so an opponent's Mental Note is never
 * offered even though the seat can see it.
 */
val archaeomancer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Archaeomancer",
                manaCost = ManaCost.parse("{2}{U}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 2),
            )

        // CR 302.1: a creature spell is cast at sorcery speed, targeting nothing — the *ability* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec =
                        TargetSpec.CardInGraveyard(
                            restriction = GraveyardCardRestriction.INSTANT_OR_SORCERY,
                            scope = GraveyardScope.YOURS,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            returnToOwnersHand(state, targetedGraveyardCard(context.targets, "Archaeomancer"))
                        },
                ),
            )
    }

/**
 * Pulse of Murasa — `{2}{G}` Instant. "Return target creature or land card from a graveyard to its
 * owner's hand. You gain 6 life."
 *
 * The first card whose *spell* targets a card in a graveyard, and the first target in the pool that may
 * be an **opponent's** card in a zone other than the battlefield ([GraveyardScope.ANY] — "a graveyard").
 * That is a targeting reach, not a visibility one: both graveyards are public (CR 400.2), so the option
 * list discloses nothing (the ADR-007 ruling on [Target.CardInGraveyard]).
 *
 * "…to **its owner's** hand" is the reason [returnToOwnersHand] is the right primitive unmodified: it
 * finds the graveyard the object is in and returns the card to *that* player's hand (CR 400.7), so
 * stealing an opponent's creature card back to their hand — which is what this line does, and which is
 * why the card is usually pointed at one's own graveyard — is not a special case.
 *
 * The lifegain is unconditional *given resolution*: if the target has become illegal the whole spell
 * fizzles (CR 608.2b) and no life is gained, which is the CR answer and needs no card-side test.
 */
val pulseOfMurasa: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Pulse of Murasa",
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec =
            TargetSpec.CardInGraveyard(
                restriction = GraveyardCardRestriction.CREATURE_OR_LAND,
                scope = GraveyardScope.ANY,
            )
        override val resolution =
            ResolutionEffect { state, context ->
                val returned =
                    returnToOwnersHand(state, targetedGraveyardCard(context.targets, "Pulse of Murasa"))
                gainLife(returned, context.controller, PULSE_OF_MURASA_LIFEGAIN)
            }
    }

/**
 * The single graveyard card an effect was told to act on (CR 115.1, CR 404). Fails loudly on anything
 * else: the CR 608.2b re-check has already run, so reaching a resolution with the wrong target shape is
 * an engine defect, not a rules corner (ADR-005) — the same contract `Removal.kt`'s permanent helper
 * keeps.
 */
private fun targetedGraveyardCard(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.CardInGraveyard)?.id
        ?: error("CR 115.1: $cardName targets exactly one card in a graveyard, got $targets")
