package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.DrawThenDiscard
import dev.mtgplay.core.definition.Ninjutsu
import dev.mtgplay.core.definition.OptionalDraw
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.tapPermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The Mono Blue Faeries tempo creatures that `FW-NINJUTSU`, `FW-TRIGCOMBAT`, and `FW-OPTDRAW` land.
 *
 * Two cards, and between them they exercise every seam the three frameworks cut: a ninjutsu cost, a
 * combat-damage-to-a-player trigger, a bare optional draw, a targeted enters-the-battlefield ability, and
 * a draw-then-discard clause hanging off an *activated* ability rather than a spell.
 */

/** The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3). */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** How many cards Ninja of the Deep Hours' combat-damage trigger may draw (CR 601.3b). */
const val NINJA_OF_THE_DEEP_HOURS_DRAW: Int = 1

/** How many cards Harrier Strix's activated ability draws before its discard (CR 601.2c). */
const val HARRIER_STRIX_LOOT_DRAW: Int = 1

/** How many cards Harrier Strix's activated ability discards after its draw (CR 701.8). */
const val HARRIER_STRIX_LOOT_DISCARD: Int = 1

/**
 * Ninja of the Deep Hours — `{3}{U}` Creature — Human Ninja, a 2/2. "Ninjutsu {1}{U} ({1}{U}, Return an
 * unblocked attacker you control to hand: Put this card onto the battlefield from your hand tapped and
 * attacking.) Whenever this creature deals combat damage to a player, you may draw a card."
 *
 * The card `FW-NINJUTSU` exists for, and the one that shows why the framework had to model CR 702.49 as an
 * **activated ability** rather than a special action: the whole point of the card in play is that the
 * opponent gets a window between the swap and the arrival.
 *
 * Every clause is declarative — this file contains no ninjutsu logic at all. The [Ninjutsu] declaration is
 * a bare cost, because CR 702.49a's ability text is the mechanic's and identical on every ninja, so the
 * engine synthesizes it (the same split madness uses for its CR 702.35b reflexive trigger). The
 * combat-damage trigger is [TriggerCondition.DealtCombatDamageToPlayerSelf] with the no-op effect and an
 * [OptionalDraw] clause: "you may draw a card" is a mid-resolution yes/no, which ADR-004 forbids a
 * [ResolutionEffect] from making, so it is a clause the engine orchestrates rather than an `if` inside an
 * effect.
 *
 * **The printed `{3}{U}` is not dead text**, even though the deck almost never pays it: the card is a real
 * creature spell, castable the ordinary way, and the ninjutsu cost is a wholly separate route onto the
 * battlefield that does not replace it (CR 702.49a is an ability, not an alternative cost — see
 * [Ninjutsu]). Both are enumerated when legal, which is the ADR-005 property that matters.
 */
val ninjaOfTheDeepHours: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ninja of the Deep Hours",
                manaCost = ManaCost.parse("{3}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Ninja")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 702.49a. The cost only; the ability text is the engine's.
        override val ninjutsu = Ninjutsu(ManaCost.parse("{1}{U}"))
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.DealtCombatDamageToPlayerSelf,
                    effect = entersTheBattlefield,
                    optionalDraw = OptionalDraw(NINJA_OF_THE_DEEP_HOURS_DRAW),
                ),
            )
    }

/**
 * Harrier Strix — `{U}` Creature — Bird, a 1/1. "Flying. When this creature enters, tap target permanent.
 * {2}{U}: Draw a card, then discard a card."
 *
 * No ninjutsu on it; it is here because it is the other half of the same tempo shell, and because it is
 * the card that proves two seams the gauntlet triage recorded as open and which have since closed:
 *
 * - **`FW-ABILTGT`** — the enters-the-battlefield trigger *targets* ([TriggeredAbility.targetSpec]), with
 *   targets chosen as the ability is put on the stack (CR 603.3d) and re-checked on resolution
 *   (CR 608.2b). The triage listed this card as blocked on exactly that, and the framework has since
 *   landed, so the card is now plain composition over the published [tapPermanent] primitive.
 * - **`FW-ABILDRAWDISCARD`** — the triage's note that `DrawThenDiscard` "lives on `SpellDefinition` only"
 *   is **stale**: `FW-CLAUSEHOOK` lifted the clauses onto a carrier that [ActivatedAbility] implements
 *   too, so the loot ability is one field rather than a framework.
 *
 * "Tap target **permanent**", not "target creature" — [PermanentRestriction.ANY_PERMANENT], so the Strix
 * can tap a land during an opponent's upkeep as readily as a blocker before combat. Reading the printed
 * line narrowly would delete the mana-denial mode the deck actually uses it for.
 */
val harrierStrix: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Harrier Strix",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Bird")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 603.3d chose the target as this went on the stack; CR 608.2b re-checked it. A
                    // permanent that has since become tapped is still a legal target and simply takes no
                    // effect (CR 701.21a) — which is the primitive's business, not this card's.
                    effect =
                        ResolutionEffect { state, context ->
                            val target =
                                context.targets.singleOrNull() as? Target.Permanent
                                    ?: error("CR 115.1: Harrier Strix's trigger taps exactly one permanent")
                            tapPermanent(state, target.id)
                        },
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT),
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{2}{U}"))),
                    // Everything the ability does is its clause, which the engine runs after this no-op.
                    effect = entersTheBattlefield,
                    drawThenDiscard = DrawThenDiscard(HARRIER_STRIX_LOOT_DRAW, HARRIER_STRIX_LOOT_DISCARD),
                ),
            )
    }
