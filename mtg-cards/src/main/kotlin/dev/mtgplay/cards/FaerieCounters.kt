package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ManaValueBound
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.counterSpell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The counter that is a creature (Mono Blue Faeries) — kept out of Counters.kt because it is not the
 * same card with a different targeting line, and Counters.kt's own header said so: it listed
 * Spellstutter Sprite as "deliberately not here … a *triggered* ability that targets, with a dynamic
 * restriction". Both halves of that have now landed, so the card can be encoded; it stays in its own
 * file because the two things it needs are not things a pure counter needs.
 */

/**
 * "X is the number of Faeries you control" (CR 109.5) — counted on the battlefield, including the
 * Sprite whose trigger is asking.
 *
 * A subtype test rather than a name test, because the card says Faeries and Mono Blue Faeries plays
 * several: Faerie Seer and Faerie Macabre are both already in the pool and both raise X.
 */
private val faeriesYouControl: ManaValueBound =
    ManaValueBound.PerMatching(
        scope = CountScope.BATTLEFIELD_YOU_CONTROL,
        predicate = ObjectPredicate.HasSubtype(Subtype("Faerie")),
    )

/**
 * Spellstutter Sprite — `{1}{U}` Creature — Faerie Wizard, 1/1. "Flash / Flying / When this creature
 * enters, counter target spell with mana value X or less, where X is the number of Faeries you
 * control."
 *
 * Three separate framework pieces meet on this one card, and it is the minimal demonstration of the
 * third.
 *
 * **A triggered ability that counters.** The counter is not the *spell's* resolution — the spell is a
 * creature spell and resolves into a permanent (CR 608.3) — but an enters-the-battlefield trigger
 * (CR 603.6a) that goes on the stack afterwards and targets at CR 603.3d. The difference is visible in
 * play: countering the Sprite *spell* stops both, but killing the Sprite in response to the *trigger*
 * stops neither, because an ability on the stack is independent of its source (CR 113.7a). It also
 * means the trigger is placed **target-less and does nothing** when no spell qualifies (CR 603.3d),
 * where an ordinary counterspell simply could not have been cast.
 *
 * **Flash, which is a timing class and not a keyword.** CR 702.8a defines flash as "you may cast this
 * spell any time you could cast an instant", so it is [TimingClass.INSTANT_SPEED] on the spell rather
 * than a `Keyword` member — the same reading `ControlledTargets.kt` records. Flash is most of the
 * card: it is what lets the Sprite be cast *in response* to the spell it will then counter.
 *
 * **A dynamic restriction, and it is dynamic in both directions.** X is
 * [ManaValueBound.PerMatching] over Faeries the controller has, re-counted at every enumeration:
 * - **The Sprite counts itself.** It is a Faerie and it is already on the battlefield when its own
 *   enters-trigger chooses targets (CR 603.6a fires *after* the entry), so a lone Sprite has X = 1 and
 *   counters a one-drop. This is the single most-misplayed thing about the card and it falls out of
 *   the enumeration rather than being special-cased.
 * - **X can shrink between the choice and the resolution.** Killing a Faerie in response to the
 *   trigger lowers X, which can make the already-chosen target illegal and fizzle the trigger
 *   (CR 608.2b). That works only because the bound is re-read at the re-check rather than captured at
 *   the choice — the enumeration is the legality test (ADR-005), so there is nowhere for a stale bound
 *   to hide.
 *
 * The restriction is **on the target**, not on the effect: a spell with too high a mana value is never
 * offered at all (ADR-005), which is the distinction `Counters.kt` insists on for the whole family.
 */
val spellstutterSprite: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Spellstutter Sprite",
                manaCost = ManaCost.parse("{1}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Faerie"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        // CR 702.8a: flash *is* "cast whenever you could cast an instant", which is this timing class.
        // There is no Keyword.FLASH, and there should not be — nothing else would read it.
        override val timing = TimingClass.INSTANT_SPEED

        // The creature spell targets nothing; the ability below does.
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.OfManaValueAtMost(faeriesYouControl)),
                    effect =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
            )
    }
