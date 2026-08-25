package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **control-restricted targeting** cards (`FW-MULTITGT`): the two whose targeting line
 * says *whose* permanent may be chosen — "target permanent you control", "target creature an opponent
 * controls".
 *
 * They arrive with the multi-target framework only because a prior packet identified them as the two
 * cards that [PermanentRestriction.PERMANENT_YOU_CONTROL] and
 * [PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS] land with no further framework work, and it was
 * right: both members are a line each in `satisfiesPermanentRestriction`, and everything else these
 * cards do — hexproof and indestructible grants, a negative power modifier, lifegain, flash — is
 * published vocabulary from `FW-DURATION` and earlier.
 *
 * What they *do* contribute to this packet is the battlefield half of a property the multi-target work
 * leans on throughout: an enumeration that depends on **who is deciding**. Until now only
 * [TargetSpec.TargetOpponent] (players) and `GraveyardScope.YOURS` (graveyard cards) were
 * decider-relative; these two make the battlefield offer each seat a different option list, which is
 * what `satisfiesPermanentRestriction`'s new `you` parameter exists for.
 */

/** The life Tamiyo's Safekeeping's controller gains (CR 119.3). */
const val TAMIYOS_SAFEKEEPING_LIFEGAIN: Int = 2

/** The power Brinebarrow Intruder's trigger subtracts (CR 613.3 sublayer 7c). */
private const val BRINEBARROW_POWER_REDUCTION: Int = -2

/** Tamiyo's Safekeeping, for the continuous effect it creates (CR 611.2). */
private val TAMIYOS_SAFEKEEPING: CardRef = CardRef("Tamiyo's Safekeeping")

/** Brinebarrow Intruder, for the continuous effect its trigger creates (CR 611.2). */
private val BRINEBARROW_INTRUDER: CardRef = CardRef("Brinebarrow Intruder")

/**
 * Tamiyo's Safekeeping — `{G}` Instant. "Target permanent you control gains hexproof and indestructible
 * until end of turn. You gain 2 life."
 *
 * The first card in the pool to print **"you control"** in a targeting line, and the first whose
 * enumeration a seat can read as "my own board only". That is the whole of its framework cost:
 * [PermanentRestriction.PERMANENT_YOU_CONTROL], one arm in `satisfiesPermanentRestriction` reading the
 * deciding player.
 *
 * **"Permanent", not "creature"** — it protects a Bogle, but it equally protects a land against Raze or
 * an Aura-laden enchantment, which is a real line in a gauntlet holding artifact removal. Encoding it as
 * `CREATURE` would be a plausible-looking wrong card (PLAN.md §7).
 *
 * **Hexproof does not narrow this enumeration, and that is the point of the card.** The `targetableBy`
 * gate removes a hexproof permanent only from an *opponent's* targeting (CR 702.11), so a GW-Bogles
 * player can always point this at their own Slippery Bogle — which is exactly the board state the card
 * is printed for, and would be unplayable if control were not part of the restriction.
 *
 * Both granted keywords ([Keyword.HEXPROOF], [Keyword.INDESTRUCTIBLE]) go into **one**
 * [ContinuousModification]: the card grants them together, from one effect with one timestamp
 * (CR 613.7d), and splitting them into two effects would order them separately for no reason any rule
 * gives. The lifegain is unconditional *given resolution* — if the target has become illegal the whole
 * spell fizzles (CR 608.2b) and no life is gained, which needs no card-side test.
 */
val tamiyosSafekeeping: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TAMIYOS_SAFEKEEPING.name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.PERMANENT_YOU_CONTROL)
        override val resolution =
            ResolutionEffect { state, context ->
                val protected =
                    applyUntilEndOfTurn(
                        state = state,
                        affected = targetedPermanentId(context.targets, TAMIYOS_SAFEKEEPING.name),
                        // CR 613.3 layer 6: both keywords are granted by one effect with one timestamp.
                        modification =
                            ContinuousModification(
                                grantedKeywords = persistentSetOf(Keyword.HEXPROOF, Keyword.INDESTRUCTIBLE),
                            ),
                        sourceCard = TAMIYOS_SAFEKEEPING,
                        source = context.source,
                    )
                gainLife(protected, context.controller, TAMIYOS_SAFEKEEPING_LIFEGAIN)
            }
    }

/**
 * Brinebarrow Intruder — `{U}` Creature — Human Rogue 1/2. "Flash. When this creature enters, target
 * creature an opponent controls gets -2/-0 until end of turn."
 *
 * The mirror of Tamiyo's Safekeeping, and the first card to print **"an opponent controls"**
 * ([PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS]). A combat trick disguised as a creature: flash
 * it in during the declare-blockers step and the attacker it points at deals two less.
 *
 * **Flash is [TimingClass.INSTANT_SPEED] on a creature spell** (CR 702.8a), not a keyword. That is not a
 * shortcut: what flash *does* is let the spell be cast whenever its controller has priority, and
 * `timingPermitsCast` reads exactly that off the timing class. There is no `Keyword.FLASH` for the same
 * reason — nothing else in the engine asks a permanent whether it has flash, because a permanent never
 * has it in any way that matters.
 *
 * **"-2/-0", not "-2/-2"**, and the toughness half stays zero: this shrinks damage without killing, so a
 * 2/2 it points at survives and simply deals nothing. [ContinuousModification] accepts the negative
 * power modifier directly (CR 613.3 sublayer 7c), and the pair is non-empty, so the guard against a
 * do-nothing effect is satisfied by the power half alone.
 *
 * **An opponent's hexproof creature is not a legal target**, and this card is the first to make that
 * subtraction observable on a restriction that is already control-scoped: `targetableBy` removes it
 * (CR 702.11) before the restriction is ever consulted. Against a GW-Bogles board of hexproof one-drops
 * the trigger frequently has no legal target at all, goes on the stack target-less (CR 603.3d), and does
 * nothing (CR 608.2b) — while the 1/2 body still enters. That is the correct reading of a mandatory
 * targeted trigger, and it is why the trigger carries the targeting rather than the spell: encoding it
 * on the spell would make the *creature* uncastable against Bogles.
 */
val brinebarrowIntruder: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = BRINEBARROW_INTRUDER.name,
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 2),
            )

        // CR 702.8a: flash *is* "cast whenever you could cast an instant", which is this timing class.
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec =
                        TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_AN_OPPONENT_CONTROLS),
                    effect =
                        ResolutionEffect { state, context ->
                            applyUntilEndOfTurn(
                                state = state,
                                affected = targetedPermanentId(context.targets, BRINEBARROW_INTRUDER.name),
                                // CR 613.3 sublayer 7c: a negative power modifier, toughness untouched.
                                modification = ContinuousModification(powerMod = BRINEBARROW_POWER_REDUCTION),
                                sourceCard = BRINEBARROW_INTRUDER,
                                source = context.source,
                            )
                        },
                ),
            )
    }

/**
 * The single permanent [targets] names (CR 115.1b). Fails loudly on anything else: the CR 608.2b
 * re-check has already run, so a resolving object whose spec is a one-target
 * [TargetSpec.TargetPermanent] always holds exactly one legal permanent target (ADR-005), and anything
 * else is an engine defect rather than a rules case.
 */
private fun targetedPermanentId(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $cardName targets exactly one permanent, got $targets")
