package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.putCounters
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's cards that put counters on permanents (CR 122.1), and the file that will grow as
 * more of them become encodable. Added by `FW-COUNTERS`.
 *
 * Naming note: this is **not** `Counters.kt`, which holds the counter*spells* (CR 701.5a). Magic
 * uses one word for two unrelated things and the engine keeps them in separate files on purpose.
 *
 * Only one card from the packet's list lands here. The other eight each need a framework this packet
 * does not own, and each stays absent rather than approximated (PLAN.md §7) — the packet report
 * names what each needs. The near misses are worth recording where a reader will look for them:
 *
 * - **Wall of Roots** ("Put a -0/-1 counter on this creature: Add {G}. Activate only once each
 *   turn.") is blocked on *cost* frameworks, not on counters: [dev.mtgplay.core.definition.ManaAbility]
 *   admits exactly two cost shapes, `{T}` and sacrifice, and there is no per-turn activation limiter
 *   anywhere in the engine. The `-0/-1` counter it would pay is already expressible —
 *   [Counter.MINUS_ZERO_MINUS_ONE].
 * - **Kenku Artificer** was this entry's longest-standing absence and is now encoded, in
 *   AwkwardSingles.kt. It needed exactly what this note recorded: CR 613 layer 4 (type change) and
 *   sublayer 7b (setting P/T), to make a noncreature artifact a 0/0 creature before its three `+1/+1`
 *   counters mean anything — and `FW-TYPECHANGE` built both. The note's sharpest observation was the one
 *   that made the work real: `Layer` did declare a `TYPE` member, but `LayeredCharacteristics`, the
 *   value the layer walk actually threads, carried **no card types or subtypes at all**, so there was
 *   nothing for a layer-4 effect to write to. It also needed an *indefinite* effect duration, which
 *   CR 611.2b makes the default and the engine could not represent.
 * - **Nyxborn Hydra** ("This permanent enters with X +1/+1 counters on it") needs bestow
 *   (`FW-BESTOW`) and a CR 614.1c *enters-with-counters* replacement, which is a third absent thing
 *   this entry used to fold into the second: placing counters is not the same mechanism as entering
 *   with them. `FW-X` since supplied the `{X}` cost, so the card is now two frameworks away rather
 *   than three.
 * - **Writhing Chrysalis** shipped in `W10-D` (`CastTriggers.kt`): the two trigger conditions it was
 *   waiting on — a stack-scoped "when you cast this spell" and a CR 701.17a "whenever you sacrifice
 *   another Eldrazi" — were built there, and its `+1/+1` counter half and its [Keyword.REACH] half were
 *   already ready when this note was written.
 */

/**
 * Unexpected Fangs — `{1}{B}` Instant. "Put a `+1/+1` counter and a lifelink counter on target
 * creature."
 *
 * The pool's first card to place counters, and the first to place a **keyword counter** (CR 122.1b) —
 * lifelink is on the closed list that rule gives, and the engine already had
 * [Keyword.LIFELINK] with its CR 702.15 damage-result effect, so the counter grants something real
 * the moment it lands.
 *
 * Two counters, two kinds, one target. They are placed in printed order and emit one
 * [dev.mtgplay.core.event.GameEvent.CountersPlaced] each, which matters only for the log — nothing
 * observes a counter being placed in this pool. Both are permanent: unlike a pump spell there is no
 * end-of-turn cleanup, because a counter is state on the object rather than a duration-bounded
 * continuous effect (CR 122.1 vs CR 611.2b). What ends them is the object changing zones, at which
 * point CR 122.2 says they simply cease to exist.
 *
 * The lifelink counter is why the card is not merely a worse Giant Growth: it survives the turn, and
 * the creature keeps lifelink for the rest of the game.
 */
val unexpectedFangs: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Unexpected Fangs",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                val creature = counterTargetCreature(context.targets)
                val withPlusOne = putCounters(state, creature, Counter.PLUS_ONE_PLUS_ONE)
                putCounters(withPlusOne, creature, Counter.KeywordCounter(Keyword.LIFELINK))
            }
    }

/**
 * The single creature Unexpected Fangs targets (CR 115.1b). On resolution the target is still legal
 * (CR 608.2b) or the spell never resolved, so anything else here is an engine defect.
 */
private fun counterTargetCreature(targets: List<Target>): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: Unexpected Fangs targets exactly one creature, got $targets")
