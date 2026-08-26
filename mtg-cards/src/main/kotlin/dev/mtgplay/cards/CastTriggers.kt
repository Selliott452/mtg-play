package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.putCounters
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The `W10-D` packet's first card, and the two trigger conditions under it (docs/decklists.md).
 *
 * **"When you cast this spell" is a different ability from "when this enters"**, and Writhing Chrysalis
 * is the pool's only card that shows the difference. Its two Eldrazi Spawn arrive from the *stack*
 * (CR 603.2), so they arrive when the Chrysalis is countered, when it is the second half of a
 * two-for-one, and when its controller has no intention of ever untapping with a 2/3 — which is the whole
 * reason a sacrifice deck plays a four-mana 2/3 at all. Encoding it as
 * [TriggerCondition.EnteredBattlefieldSelf] would build the same board on an uncontested cast and a
 * different one on every cast that mattered: the plausible-looking wrong card PLAN.md §7 forbids.
 *
 * [TriggerCondition.CastSelf] is therefore a stack-scoped ability, joining cascade and storm on
 * [TriggerZoneScope.Stack] — and, like both, it is synthesized by the casting pipeline rather than
 * detected, because nothing in this engine scans the stack (`SelfCastTrigger.kt`). Unlike both, the
 * ability is the card's rather than the engine's: it carries this file's [ResolutionEffect] and resolves
 * through the ordinary trigger path.
 *
 * [TriggerCondition.YouSacrificedAnother] is the other half, and it is the engine's **first trigger that
 * watches a sacrifice** (CR 701.17a) and its **first with a subtype axis** (CR 205.3). Both were recorded
 * as blockers and both turned out to be one detection site each, for a reason worth keeping: every
 * sacrifice in the engine — cost, effect, bargain, an opponent-sacrifices clause, a token's own mana
 * ability — funnels through one private function, so the watcher has one home and a sacrifice path added
 * later cannot forget it (`SacrificeTriggers.kt`).
 *
 * **Devoid is not a blocker and never was.** [Keyword.DEVOID] exists and
 * [PrintedCharacteristics.colors] reads it, which is the CR-correct treatment as well as the cheap one:
 * CR 702.114a makes devoid a characteristic-defining ability functioning in every zone, including the
 * zones CR 613's layer system does not reach. [Keyword.REACH] and the `+1/+1` counter are both older
 * still, and the Eldrazi Spawn token with its "sacrifice this token: add {C}" mana ability has been in
 * [eldraziSpawnToken] since Malevolent Rumble.
 */

/** How many Eldrazi Spawn tokens Writhing Chrysalis's cast trigger creates (CR 603.2). */
const val WRITHING_CHRYSALIS_SPAWN: Int = 2

/**
 * Writhing Chrysalis — `{2}{R}{G}` Creature — Eldrazi Drone 2/3. "Devoid. When you cast this spell,
 * create two 0/1 colorless Eldrazi Spawn creature tokens with 'Sacrifice this token: Add {C}.' Reach.
 * Whenever you sacrifice another Eldrazi, put a `+1/+1` counter on this creature."
 *
 * **Four mana for three bodies and a mana engine.** The Spawn are the point: two of them sacrifice for
 * `{C}` each, which is how a Jund or Gates deck turns a stalled board into a fifth and sixth mana — and
 * each one sacrificed is *another Eldrazi*, so the Chrysalis grows itself by eating its own tokens. The
 * card is a self-contained loop, and both halves of it are new engine.
 *
 * **The cast trigger resolves before the creature does** (CR 603.2, CR 601.2i): it goes on the stack
 * *above* the Chrysalis, so both Spawn are on the battlefield while the creature spell is still an object
 * on the stack — targetable, counterable, and irrelevant to the tokens either way. The controller orders
 * it against anything else that fired from the same cast (CR 603.3b).
 *
 * **"Another" is doing real work here, not scaffolding.** A sacrifice deck's most natural line is to feed
 * the Chrysalis itself to something; the exclusion is what stops the engine from putting a counter on a
 * permanent that is in the middle of leaving. The counter therefore lands only on a Chrysalis still on the
 * battlefield, which the effect below checks a second time for the CR 603.10 case the condition cannot:
 * the trigger fired legally and the source died in response, in which case the ability resolves and does
 * nothing (CR 608.2b).
 *
 * **The Spawn's mana ability is not this card's**, and CR 707.2 is why: the token carries its own
 * `{C}`-producing sacrifice-self mana ability, and creating it is the whole of this trigger's effect.
 * A Spawn sacrificed for mana fires the second trigger on the way out, because a mana ability's cost is a
 * sacrifice like any other (CR 701.17a) and goes through the same funnel.
 */
val writhingChrysalis: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Writhing Chrysalis",
                manaCost = ManaCost.parse("{2}{R}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Eldrazi"), Subtype("Drone")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 3),
                // CR 702.114a: devoid is a characteristic-defining ability, so the card is colourless in
                // every zone despite the {R} and {G} in its cost. CR 702.17a: reach.
                keywords = persistentSetOf(Keyword.DEVOID, Keyword.REACH),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: a creature spell's resolution is its own entry onto the battlefield, which the engine
        // performs; the card contributes nothing.
        override val resolution = ResolutionEffect { state, _ -> state }

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    // CR 603.2: an ability of the *spell*, functioning from the stack, so the tokens
                    // arrive whether or not the creature ever does.
                    condition = TriggerCondition.CastSelf,
                    zoneScope = TriggerZoneScope.Stack,
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 111.1: two separate tokens, created one at a time — "two ... tokens" is
                            // two objects, not one object with a count.
                            (1..WRITHING_CHRYSALIS_SPAWN).fold(state) { current, _ ->
                                createToken(current, context.controller, eldraziSpawnToken)
                            }
                        },
                ),
                TriggeredAbility(
                    // CR 603.2, CR 701.17a: "whenever you sacrifice another Eldrazi". The subtype is
                    // matched through the engine's layer-4 seam, so a permanent granted the type counts.
                    condition = TriggerCondition.YouSacrificedAnother(Subtype("Eldrazi")),
                    effect =
                        ResolutionEffect { state, context ->
                            val source = context.source
                            // CR 603.10, CR 608.2b: the trigger fired against the pre-sacrifice state and
                            // may resolve after its own source has left. Placing a counter then is not a
                            // partial effect but an impossible one, so the ability does as much as it can,
                            // which is nothing.
                            if (source == null || state.sharedZones.battlefield.none { it.id == source }) {
                                state
                            } else {
                                putCounters(state, source, Counter.PLUS_ONE_PLUS_ONE)
                            }
                        },
                ),
            )
    }
