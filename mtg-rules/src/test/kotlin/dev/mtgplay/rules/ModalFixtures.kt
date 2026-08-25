package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.counterSpell
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import dev.mtgplay.rules.effect.targetIsColor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Fixture definitions for the `FW-MODAL` specs (docs/design/countering-spells.md §8). `mtg-rules` names
 * no real card (ADR-003), so the mode decision, the CR 601.2b-before-CR 601.2c ordering, and the two
 * Blast templates are exercised against synthetic modal spells here; the five real cards live in
 * `mtg-cards`.
 *
 * **The pair that matters is [fixtureRestrictedBlast] and [fixtureConditionalBlast].** They are printed
 * to be as nearly identical as two cards can be — same cost, same two modes, same colour, same
 * primitives — and differ in exactly one thing: whether the colour test sits in the *targeting line* or
 * in the *effect*. That one difference is what the enumeration specs measure, and building both from the
 * same shape here is what makes the measurement a controlled comparison rather than two unrelated cards
 * that happen to behave differently.
 */

/**
 * "Fixture Restricted Blast" — `{U}` instant, the **target-restricted** template (Blue Elemental Blast's
 * shape): "Choose one — Counter target red spell; or Destroy target red permanent."
 *
 * The colour lives in both [TargetSpec]s, so the engine filters the option list and an ineligible object
 * is never offered (ADR-005). With no red object on the stack or the battlefield, *neither* mode has a
 * legal target and the card is not castable at all.
 */
internal val fixtureRestrictedBlast: SpellDefinition =
    object : ModalSpell {
        override val characteristics = modalCharacteristics("Fixture Restricted Blast", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target red spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.OfColor(Color.RED)),
                    resolution =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
                SpellMode(
                    text = "Destroy target red permanent.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.RED_PERMANENT),
                    resolution = ResolutionEffect { state, context -> destroy(state, permanentId(context.targets)) },
                ),
            )
    }

/**
 * "Fixture Conditional Blast" — `{U}` instant, the **effect-conditional** template (Hydroblast's shape):
 * "Choose one — Counter target spell if it's red; or Destroy target permanent if it's red."
 *
 * Identical to [fixtureRestrictedBlast] but for the one thing under test: both [TargetSpec]s are
 * unrestricted and the colour is tested by [targetIsColor] at resolution. So every spell on the stack
 * and every permanent on the battlefield is a legal choice, the card is castable whenever *anything* is
 * targetable, and against a non-red target it resolves and does nothing.
 */
internal val fixtureConditionalBlast: SpellDefinition =
    object : ModalSpell {
        override val characteristics = modalCharacteristics("Fixture Conditional Blast", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target spell if it's red.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any),
                    resolution =
                        ResolutionEffect { state, context ->
                            val target = context.targets.single()
                            if (targetIsColor(state, target, Color.RED)) {
                                counterSpell(state, target, context.source)
                            } else {
                                state
                            }
                        },
                ),
                SpellMode(
                    text = "Destroy target permanent if it's red.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT),
                    resolution =
                        ResolutionEffect { state, context ->
                            val target = context.targets.single()
                            if (targetIsColor(state, target, Color.RED)) {
                                destroy(state, permanentId(listOf(target)))
                            } else {
                                state
                            }
                        },
                ),
            )
    }

/**
 * "Fixture Sabotage" — `{U}` instant (Steel Sabotage's shape): "Choose one — Counter target artifact
 * spell; or Return target artifact to its owner's hand."
 *
 * The fixture whose two modes are *different actions* rather than the same action on two kinds of
 * object, and the one that exercises [returnPermanentToOwnersHand].
 */
internal val fixtureSabotage: SpellDefinition =
    object : ModalSpell {
        override val characteristics = modalCharacteristics("Fixture Sabotage", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target artifact spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.OfCardType(CardType.ARTIFACT)),
                    resolution =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
                SpellMode(
                    text = "Return target artifact to its owner's hand.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT),
                    resolution =
                        ResolutionEffect { state, context ->
                            returnPermanentToOwnersHand(state, permanentId(context.targets))
                        },
                ),
            )
    }

/**
 * "Fixture Charm" — `{U}` instant whose **second mode targets nothing**: "Choose one — Counter target
 * spell; or Nothing happens."
 *
 * No real card in the pool has a targetless mode, and this fixture exists because the *engine* must
 * handle one: choosing it has to settle the cast's targets empty rather than surfacing an empty
 * `ChooseTargets` request, which is the one branch of [dev.mtgplay.core.state.PendingCast] that only a
 * targetless mode reaches. Without it the "settle targets from the chosen mode" step would be untested
 * in one direction.
 */
internal val fixtureCharm: SpellDefinition =
    object : ModalSpell {
        override val characteristics = modalCharacteristics("Fixture Charm", "{U}")
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any),
                    resolution =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
                SpellMode(
                    text = "Nothing happens.",
                    targetSpec = TargetSpec.None,
                    resolution = ResolutionEffect { state, _ -> state },
                ),
            )
    }

/**
 * "Fixture Prayer" — a `{W}` instant: the **white** spell Pyroblast's template must still be able to
 * target. Its whole job is to be a legal target that the colour condition then declines to act on.
 */
internal val fixturePrayer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics = modalCharacteristics("Fixture Prayer", "{W}")
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
    }

/** The single permanent a mode's settled target names; loud on anything else (ADR-005). */
private fun permanentId(targets: List<Target>) =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: this mode targets exactly one permanent, got $targets")

/** The printed characteristics of an instant fixture with no P/T box (CR 304). */
private fun modalCharacteristics(
    name: String,
    cost: String,
): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = ManaCost.parse(cost),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.INSTANT),
        subtypes = persistentSetOf(),
        powerToughness = null,
    )

/**
 * The `FW-MODAL` fixtures merged onto the `FW-COUNTER` registry, ready for `fixtureState`.
 *
 * [fixtureRelic] comes from `CostModificationFixtures.kt` rather than being redeclared here: a plain
 * `{1}` artifact is exactly the artifact spell and artifact permanent Fixture Sabotage's two modes need,
 * and a second one under the same printed name would be two cards the registry could not tell apart.
 */
internal val modalDefinitions: Map<CardRef, CardDefinition> =
    counterDefinitions +
        listOf(
            fixtureRestrictedBlast,
            fixtureConditionalBlast,
            fixtureSabotage,
            fixtureCharm,
            fixtureRelic,
            fixturePrayer,
        ).associateBy { CardRef(it.characteristics.name) }
