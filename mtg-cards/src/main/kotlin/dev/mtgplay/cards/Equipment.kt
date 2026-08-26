package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.attachPermanent
import dev.mtgplay.rules.effect.gainEnergy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Equipment (CR 301.5, CR 702.6) and energy (CR 107.16) — `FW-EQUIP`.
 *
 * **The blocker was attachment as a *verb*, not as a field.** `GameObject.attachedTo` has existed since
 * P4.1, and "equipped creature gets +2/+0" is an ordinary CR 613 sublayer-7c static over
 * [AffectedSet.Enchanted], which is exactly what that field means. What did not exist was any way to
 * attach a permanent that was *already on the battlefield*: every path that wrote the field was
 * Aura-shaped — an Aura spell resolves against a `TargetSpec.Enchantable` and the engine attaches it as
 * the permanent enters (CR 303.4f), once, forever. Equip moves an attachment on a resolved permanent,
 * repeatedly, and so does Inventor's Axe's own enters trigger.
 *
 * **CR 704.5n is the second half and is the opposite of CR 704.5m.** A dangling Aura is put into its
 * owner's graveyard; a dangling Equipment becomes unattached and **stays on the battlefield**. That
 * difference *is* the difference between the two card types — it is why a deck plays an Equipment — so
 * the two state-based actions are separate members of the hierarchy rather than one shared "dangling
 * attachment" action.
 *
 * **Energy** (CR 107.16) is the smaller half and was never the reason this card was dropped: it is a
 * counter on the *player*, so `PlayerState` gained an `energyCounters` total, `AbilityCost` gained an
 * `Energy` member, and the activation pipeline pays it. It surfaces no decision, because there is
 * exactly one way to pay it.
 */

/** The artifact subtype that makes an artifact an Equipment (CR 301.5a). */
private val EQUIPMENT: Subtype = Subtype("Equipment")

/** How much energy Inventor's Axe gives and how much its equip cost pays (CR 107.16). */
private const val INVENTORS_AXE_ENERGY: Int = 2

/** The power bonus the equipped creature gets (CR 613 sublayer 7c). */
private const val INVENTORS_AXE_POWER_BONUS: Int = 2

/** "Target creature you control" — what both the enters trigger and equip may point at (CR 702.6b). */
private val TARGET_CREATURE_YOU_CONTROL: TargetSpec =
    TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)

/**
 * Inventor's Axe — `{R}` Artifact — Equipment. *"Flash. When this Equipment enters, you get `{E}{E}`
 * (two energy counters). When this Equipment enters, attach it to target creature you control. Equipped
 * creature gets +2/+0. Equip—Pay `{E}{E}`."*
 *
 * A one-mana `+2/+0` that arrives already attached and can be moved exactly once more for free. The
 * whole card is an argument about **tempo versus a two-for-one**: an Aura for one mana giving `+2/+0`
 * would be strictly worse, because killing the creature in response eats the Aura too (CR 704.5m). This
 * is the same rate that survives its host — CR 704.5n unattaches it and leaves it on the battlefield —
 * and the two energy counters are the price of moving it on.
 *
 * **Flash is the reason the +2/+0 is a combat trick and not a build-around** (CR 702.8a). Cast in the
 * declare-blockers step, the Axe enters, its second trigger goes on the stack, and it attaches to an
 * already-attacking creature — the Aura line, at instant speed, for one mana.
 *
 * **Two separate enters triggers, not one with two clauses**, because the card prints two "When this
 * Equipment enters" lines and CR 603.1 makes each of them its own ability. It is not pedantry: they go
 * on the stack as two objects, in an order their controller chooses (CR 603.3b), and only the second one
 * targets — so a board with no creature on it loses the attach trigger to CR 608.2b's no-legal-target
 * removal while the energy trigger resolves regardless. Folding them into one clause would lose the
 * energy on an empty board, which is exactly the position a topdecked Axe is in.
 *
 * **Equip is sorcery-timed and the enters trigger is not** (CR 702.6b), which is the card's one real
 * decision. The free attach happens whenever the Axe enters, instant speed included; moving it
 * afterwards costs `{E}{E}` and may only be done at sorcery speed. So the energy is not spare change —
 * it is precisely one re-equip, and a controller who wants the Axe on a different creature during combat
 * has to have planned it a turn earlier.
 *
 * **Equip targets a creature *you control*** (CR 702.6b), which is what stops the Axe from being handed
 * to an opponent's blocker, and the engine enumerates exactly those (ADR-005). The `+2/+0` itself is
 * declared as an ordinary static over [AffectedSet.Enchanted] and needs no Equipment-specific machinery
 * at all: "equipped creature" and "enchanted creature" are the same object relation with two names.
 */
val inventorsAxe: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Inventor's Axe",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                // CR 301.5a: Equipment is an artifact subtype, and it is the *only* thing that marks an
                // Equipment — the CR 704.5n state-based action reads exactly this word.
                subtypes = persistentSetOf(EQUIPMENT),
                powerToughness = null,
            )

        // CR 702.8a: flash is a timing permission, not a printed keyword — the pool's settled spelling.
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: a permanent spell with no instructions of its own resolves by entering.
        override val resolution = ResolutionEffect { state, _ -> state }

        override val staticContinuousEffects =
            persistentListOf(
                // CR 613.4c: "Equipped creature gets +2/+0" — an ordinary sublayer-7c static over the
                // attached object, identical in shape to an Aura's, because attachment is one relation.
                StaticContinuousEffect(
                    affects = AffectedSet.Enchanted,
                    powerMod = Magnitude.Fixed(INVENTORS_AXE_POWER_BONUS),
                ),
            )

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainEnergy(state, context.controller, INVENTORS_AXE_ENERGY)
                        },
                ),
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TARGET_CREATURE_YOU_CONTROL,
                    effect =
                        ResolutionEffect { state, context ->
                            attachToTarget(state, context.source, context.targets)
                        },
                ),
            )

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // "Equip—Pay {E}{E}" (CR 702.6b): the cost is energy, not mana, so no payment plan
                    // is enumerated and no mana source is reserved.
                    cost = persistentListOf(AbilityCost.Energy(INVENTORS_AXE_ENERGY)),
                    // CR 702.6b: "Equip only as a sorcery."
                    timing = TimingClass.SORCERY_SPEED,
                    targetSpec = TARGET_CREATURE_YOU_CONTROL,
                    effect =
                        ResolutionEffect { state, context ->
                            attachToTarget(state, context.source, context.targets)
                        },
                ),
            )
    }

/**
 * Attaches the Axe ([source]) to the chosen creature (CR 701.3a) — the resolution both the enters
 * trigger and the equip ability share, because they do the same thing and differ only in what it costs
 * to get there.
 *
 * Resolves to nothing when the ability chose no target or its source is gone. Neither is defensive
 * padding: a trigger placed with no legal target is removed from the stack (CR 603.3d) and an ability
 * whose source has left the battlefield still resolves from last-known information (CR 113.7c) with
 * nothing to attach, so both are rules cases rather than engine defects.
 */
private fun attachToTarget(
    state: GameState,
    source: ObjectId?,
    targets: List<Target>,
): GameState {
    val target = targets.singleOrNull()
    if (target != null) {
        check(target is Target.Permanent) { "CR 702.6b: equip targets a creature you control, got $target" }
    }
    val equipment = source.takeIf { it != null && state.sharedZones.battlefield.any { obj -> obj.id == it } }
    return if (target is Target.Permanent && equipment != null) {
        attachPermanent(state, equipment, target.id)
    } else {
        state
    }
}
