package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.EntersWithCounters
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Bestow (CR 702.103) — the gauntlet's one printing, and the last of the deferred ten to need a
 * mechanic of its own. `W10-C`.
 *
 * **What bestow actually is, and why the recorded diagnosis called it a framework.** A creature card
 * with bestow may be cast as an *Aura spell* for its bestow cost. That single sentence is four separate
 * facts, and every one of them had to become expressible:
 *
 * 1. **How a spell was cast changes what it targets.** A Nyxborn Hydra cast for `{X}{G}` is a creature
 *    spell that targets nothing; the same card cast for `{X}{G}{G}` is an Aura spell that must name a
 *    creature (CR 601.2c, CR 702.103b). Nothing in the engine had ever made the targeting line depend on
 *    the *cost paid* — modes did it, permissions did not — so the casting permission is now threaded
 *    through the whole targeting seam (`SpellModes.kt`'s `specInForce`).
 * 2. **It enters attached.** Free: the Aura attachment read (CR 303.4f) already went through the
 *    spec-in-force, so a bestow cast reaches it as an [TargetSpec.Enchantable] and attaches.
 * 3. **It is an Aura and not a creature while attached** — a CR 613 **layer 4** static ability, and the
 *    first in the engine that *removes* a card type. Two packets had declined to add the field because no
 *    card printed the form; this one does.
 * 4. **It becomes a creature again when it comes off** (CR 702.103c), which is the whole reason bestow
 *    is not an expensive Aura. This needed *two* things and neither is a special case: the type change
 *    had to be conditioned on being attached (so it stops applying the instant the host leaves, with
 *    nothing on the stack), and the CR 704.5m graveyard state-based action had to be kept away from it in
 *    favour of CR 702.103c's unattach-and-stay (`BestowAttachment.kt`).
 *
 * The fifth requirement is not bestow at all: **"enters with X `+1/+1` counters"** is CR 614.1c, the
 * replacement shape `ReplacementEffect`'s KDoc had listed as an unbuilt extension point for four
 * packets. It is a replacement rather than a trigger for a reason this card makes vivid — a 0/1 Hydra
 * whose counters arrived by trigger would be a 0/1 with a trigger on the stack, and one cast for X = 0
 * would be a legal 0/1, but the general shape ("enters with N counters") applied to a 0/0 body would die
 * to CR 704.5f before its counters landed.
 *
 * Oracle text re-read from the repo's Scryfall snapshot before any code was written. The packet brief's
 * account of this card is accurate; the only correction is to its *diagnosis*, which said the counters
 * framework "supplies neither" of the two halves — true when written, and the enters-with half turned
 * out to cost one declaration and one line at the entry seam rather than a framework.
 */

/** Nyxborn Hydra's printed body before its counters (CR 208.1) — the 0/1 an X of zero leaves behind. */
const val NYXBORN_HYDRA_PRINTED_POWER: Int = 0

/** Nyxborn Hydra's printed toughness (CR 208.1). */
const val NYXBORN_HYDRA_PRINTED_TOUGHNESS: Int = 1

/**
 * A dynamic magnitude of one per `+1/+1` counter on the **effect's own source** (CR 613.3c, CR 122.1a) —
 * Nyxborn Hydra's "enchanted creature gets +1/+1 for each +1/+1 counter on **this Aura**".
 *
 * **The counters are on the Aura and the bonus is on something else**, which is what makes this
 * different from every other dynamic magnitude in the pool: Ethereal Armor counts permanents on the
 * battlefield, and this counts a multiset on one specific object — the one generating the effect. It is
 * therefore read off [source] rather than off the affected object, and getting that backwards would give
 * the enchanted creature a bonus for *its own* counters, which is a different and much better card.
 *
 * Read live on every characteristic computation (CR 613.3c), so an effect that added or removed a
 * counter on the Aura changes the enchanted creature's power in the same instant. A source that has left
 * the battlefield contributes nothing, which cannot happen while the effect is active — an unattached
 * bestowed permanent has an empty affected set — and is answered rather than thrown for the same reason
 * the sibling magnitudes answer it.
 */
private val perPlusOneCounterOnThisAura: Magnitude =
    Magnitude.Dynamic { state, source ->
        state.sharedZones.battlefield
            .firstOrNull { it.id == source }
            ?.counterCount(Counter.PLUS_ONE_PLUS_ONE)
            ?: 0
    }

/**
 * Nyxborn Hydra — `{X}{G}` Enchantment Creature — Hydra, a printed 0/1.
 *
 * "Bestow `{X}{G}{G}`" / "Reach, trample" / "This permanent enters with X `+1/+1` counters on it." /
 * "Enchanted creature gets +1/+1 for each +1/+1 counter on this Aura and has reach and trample."
 *
 * **One card, two permanents.** Cast for `{X}{G}` it is an X+0/X+1 Hydra with reach and trample. Cast for
 * `{X}{G}{G}` it is an Aura that gives its host +X/+X, reach and trample — and when that host leaves, it
 * *becomes the Hydra*, counters and all, instead of going to a graveyard. Both halves are the same
 * printed lines read under different rules, which is why the encoding is one definition rather than two:
 *
 * - The **enters-with-counters** clause (CR 614.1c) is read at the moment of entry whichever cost was
 *   paid, so the Aura carries the same X counters the creature would have, and those counters are
 *   exactly what the fourth line then counts.
 * - The **fourth line** is a [StaticContinuousEffect] over [AffectedSet.Enchanted], so it is active
 *   precisely while the permanent is attached to something — which a normally-cast Hydra never is. No
 *   condition is needed to switch it off, and adding one would be a second answer to a question the
 *   affected set already answers.
 * - The **type change** is the [StaticContinuousEffect] over [AffectedSet.Self], conditioned on
 *   [StaticCondition.AttachedToCreature] (CR 702.103a). While it holds, the permanent is an Aura
 *   enchantment and **not** a creature: it cannot attack, cannot block, is not a legal target for
 *   "destroy target creature", and is not counted by a creature-counting filter. The instant it stops
 *   holding, all of that reverses, with no trigger and no priority in between (CR 604.3).
 *
 * **What the printed reach and trample on the type line are for.** They are the *Hydra's* keywords and
 * matter only while it is a creature; the identically-named grants on the fourth line are given to the
 * enchanted creature and are a different ability. Encoding either one as the other would be right in one
 * of the card's two shapes and silently wrong in the other.
 *
 * **The bestow cost is `{X}{G}{G}` and its X is the X.** CR 702.103b makes the bestow cost the cost the
 * spell is cast for, so the announcement that prices the cast is the same number the CR 614.1c clause
 * reads — a Hydra bestowed for X = 3 costs `{3}{G}{G}` and enters with three counters, giving its host
 * +3/+3. There is no second variable and no rule needed to relate them; both read
 * [dev.mtgplay.core.state.StackEntry.Spell.chosenX].
 */
val nyxbornHydra: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Nyxborn Hydra",
                manaCost = ManaCost.parse("{X}{G}"),
                supertypes = persistentSetOf(),
                // CR 205.1b: an enchantment creature is both, which is what lets the layer-4 ability
                // take the creature type away and leave a legal Aura enchantment behind.
                cardTypes = persistentSetOf(CardType.ENCHANTMENT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Hydra")),
                powerToughness =
                    PrintedPowerToughness(
                        power = NYXBORN_HYDRA_PRINTED_POWER,
                        toughness = NYXBORN_HYDRA_PRINTED_TOUGHNESS,
                    ),
                keywords = persistentSetOf(Keyword.REACH, Keyword.TRAMPLE),
            )

        // CR 702.103b: the alternative cost that makes the spell an Aura spell with enchant creature.
        override val castingPermissions =
            persistentListOf<CastingPermission>(CastingPermission.Bestow(ManaCost.parse("{X}{G}{G}")))

        // CR 614.1c: "This permanent enters with X +1/+1 counters on it" — a self-replacement on the
        // entering event, so the body is never seen without them.
        override val entersWithCounters =
            EntersWithCounters(counter = Counter.PLUS_ONE_PLUS_ONE, amount = CounterAmount.AnnouncedX)

        // CR 302.1: the creature spell itself is sorcery-speed and targets nothing. The *bestow* cast
        // targets a creature, and that spec is put in force by the permission rather than declared here
        // — one card, two targeting lines, decided by the cost paid (CR 702.103b).
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        override val staticContinuousEffects =
            persistentListOf(
                // CR 702.103a + CR 613.1d: "as long as this permanent is attached to a creature, it's an
                // Aura enchantment and not a creature". The first removing type change in the engine.
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = StaticCondition.AttachedToCreature,
                    addedSubtypes = persistentSetOf(Subtype("Aura")),
                    removedCardTypes = persistentSetOf(CardType.CREATURE),
                ),
                // The printed Aura line: CR 613.3 sublayer 7c for the bonus, layer 6 for the two grants.
                // Active exactly while the permanent is attached, which [AffectedSet.Enchanted] already
                // says — a Hydra cast for its printed cost is attached to nothing and grants nobody
                // anything.
                StaticContinuousEffect(
                    affects = AffectedSet.Enchanted,
                    grantedKeywords = persistentSetOf(Keyword.REACH, Keyword.TRAMPLE),
                    powerMod = perPlusOneCounterOnThisAura,
                    toughnessMod = perPlusOneCounterOnThisAura,
                ),
            )
    }
