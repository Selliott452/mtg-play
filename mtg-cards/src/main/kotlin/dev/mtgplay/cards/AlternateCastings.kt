package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.powerOfOrLastKnown
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Monster Tron's top end — the two cards whose printed line is an *alternative way to cast a card*
 * rather than an effect, and the reason the deck was the gauntlet's last to reach playable
 * (docs/gauntlet-deferred-ten.md).
 *
 * Both were triaged as drops on the grounds that each needs "a mechanic no other gauntlet card
 * touches". Re-examined here, one of the two blockers was **stale** and the other was **misdiagnosed**:
 *
 * - *"The engine has no 'cast without paying its mana cost' path at all — nothing in `mtg-core` or
 *   `mtg-rules` matches, and [CastingPermission] … is uniformly a permission to cast for some cost,
 *   never for none."* That was already false when it was written. [CastingPermission.Plot] and
 *   [CastingPermission.Rebound] both fix their cost at `{0}` with the comment *"cast without paying its
 *   mana cost — a `{0}` cost yields a single empty payment plan"*, and Ephemerate's rebound had been
 *   driving that path end to end since `FW-BLINK`. Cascade's free cast needed **no** new cast
 *   machinery: it is `Rebound`'s flow with a different card in it.
 * - Prototype was filed as *"a CR 613 layer 1/7b effect keyed to how the spell was cast"*, which would
 *   make it wait on the layer system. CR 718.2a says the opposite — the alternative characteristics are
 *   **copiable values**, so a prototyped object has no effect applied to it at all and simply starts
 *   from a different base. See `Prototype.kt` in `mtg-rules` for the seam that follows from that.
 *
 * What cascade genuinely needed was the other two absences the triage named and got right: exiling from
 * a library *until a predicate holds*, and a seeded shuffle of a known set on the way to the bottom of
 * that library (ADR-006 — `Rng`, never `kotlin.random`). Both live in `Cascade.kt`.
 *
 * ## The two cards this packet did **not** encode, and how they landed
 *
 * **Fang Dragon** (`{5}{R}{R} // {1}{R}`, Creature — Dragon // Sorcery — Adventure 6/3) and **Sagu
 * Wildling** (`{4}{G} // {G}`, Creature — Dragon // Sorcery — Omen 3/3) were dropped here, with the
 * triage's diagnosis restated as it stood: *"one card with two castable halves … `CastingPermission`
 * could carry the permission; `PrintedCharacteristics` cannot carry two faces, and that is the real
 * blocker."* `W10-B` picked them up and both cards are now encoded (TwoFacedCards.kt). **The blocker
 * was real; both of the shapes this note predicted it would force were avoidable**, and the correction
 * is worth more than either card:
 *
 * - This note said the choice was *"either a second definition slot on the contract or a second
 *   registry key, and **either choice reaches every card in the pool**"*. Neither happened. A face is a
 *   whole second [SpellDefinition] on [SpellDefinition.alternativeFace], and the *cast* substitutes it
 *   for the card's — which is one function, [dev.mtgplay.rules.engine.castDefinitionOf], at the five
 *   places the CR 601 pipeline already reached a definition through. Every downstream read
 *   (the CR 111/613 stack seam, the permanent-spell test, the targeting lines, the CR 608.2b fizzle, the
 *   resolution fold) goes through the cast record's definition and became face-aware with **no edit of
 *   its own**. [dev.mtgplay.core.card.PrintedCharacteristics] grew nothing, and
 *   [dev.mtgplay.core.identity.CardRef] stays the card's name in every zone (CR 715.2c: an adventurer
 *   card is *one* card), so no CR 400.7 zone move had to decide which key an object carries.
 * - The comparison with prototype above is **exactly right** and worth keeping: an Adventure's faces do
 *   have different names, card types and resolutions, which is why a face could not ride inside a
 *   [CastingPermission] the way `Prototype`'s three values do. What the note did not say is the reason
 *   that matters most in practice — a permission has to **round-trip the wire** (ADR-005: an option is
 *   sent to a seat and comes back), and a resolution effect is a function value. That is what settles
 *   the face onto the *card* and leaves the permission carrying a cost and a name.
 * - [SpellMode] was named as "the nearest existing shape and wrong", and that stands unchanged.
 * - The exile marker was called "not the blocker … a morning's work *if faces existed*", and that was
 *   accurate to the hour: [dev.mtgplay.core.state.GameObject.onAnAdventure] is the fifth exile marker
 *   and CR 715.3d's grant is enumerated beside Reckless Impulse's, off the marker rather than off a
 *   permission. The **Omen** half needed even less — CR 720.3d shuffles the card into its owner's
 *   library instead, so there is no marker and no cast from exile at all, only a third destination on
 *   the one function that takes a spell off the stack.
 *
 * One thing this note got wrong on the facts: it cites *"CR 715.3d, CR 731.2"* for the omen shuffle.
 * Omen cards are **CR 720**; CR 716 is Class Cards and CR 731 is something else again.
 */

/** Boulderbranch Golem, for its printed and prototyped identities (CR 201.1). */
private const val BOULDERBRANCH_GOLEM_NAME: String = "Boulderbranch Golem"

/** The prototyped size of Boulderbranch Golem (CR 718.2) — `Prototype {3}{G} — 3/3`. */
private const val BOULDERBRANCH_PROTOTYPE_SIZE: Int = 3

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. Both cards in this file do their printed work
 * through a triggered ability, never through a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Boulderbranch Golem — `{7}` Artifact Creature — Golem 6/5. "Prototype `{3}{G}` — 3/3 (You may cast
 * this spell with different mana cost, color, and size. It keeps its abilities and types.) When this
 * creature enters, you gain life equal to its power."
 *
 * Monster Tron's curve-smoother, and the card docs/gauntlet-deferred-ten.md named as "the one to revisit
 * first". Two genuinely different cards in one: a turn-four `{3}{G}` green 3/3 that gains 3 life, or a
 * turn-seven `{7}` colourless 6/5 that gains 6. **Both lines are enumerated** (ADR-005) — the prototyped
 * cast is a [CastingPermission] offered beside the normal one, exactly as Fireblast's alternative cost
 * is, and a seat that can afford both sees both.
 *
 * **The printed line the encoding could most easily have deleted is "and size".** An alternative cost
 * that changed only the mana cost would produce a `{3}{G}` **6/5** that gains 6 life — a card strictly
 * better than the one printed, and the kind of plausible-looking wrong card PLAN.md §7 treats as worse
 * than an absence. CR 718.3b is explicit that a prototyped spell *and the permanent it becomes* have
 * **only** the alternative power, toughness and mana cost, so the engine carries the fact across the
 * CR 400.7 spell→permanent boundary ([dev.mtgplay.core.state.GameObject.prototyped]) and reads the
 * permanent's base characteristics through it.
 *
 * **"You gain life equal to its power" is what makes the size observable rather than cosmetic**, and it
 * is why the trigger reads the *live* power through [powerOfOrLastKnown] rather than a constant. Three
 * different answers are all reachable from the same printed line: 3 for a prototyped body, 6 for a
 * printed one, and something else again for a body that has been pumped or shrunk in response to the
 * trigger — the amount is determined as the ability resolves (CR 608.2h), not as it fires. The
 * last-known fallback covers the fourth case, a Golem answered by removal before its own trigger
 * resolves.
 *
 * The colour half of CR 718.3b needs no declaration here: colour derives from the mana cost (CR 202.2),
 * so replacing the cost is what makes this colourless artifact creature **green** while it is a
 * prototyped spell and afterwards as a prototyped permanent. That matters to Monster Tron in exactly one
 * direction — it is why the deck can cast the Golem early off a Forest at all.
 */
val boulderbranchGolem: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = BOULDERBRANCH_GOLEM_NAME,
                manaCost = ManaCost.parse("{7}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Golem")),
                powerToughness = PrintedPowerToughness(power = 6, toughness = 5),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 702.160a: "You may cast this spell with different mana cost, color, and size." The colour
        // is not declared — it follows from the mana cost (CR 202.2, CR 718.3b).
        override val castingPermissions =
            listOf(
                CastingPermission.Prototype(
                    cost = ManaCost.parse("{3}{G}"),
                    power = BOULDERBRANCH_PROTOTYPE_SIZE,
                    toughness = BOULDERBRANCH_PROTOTYPE_SIZE,
                ),
            )

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 603.10: the entered permanent is the trigger's subject. CR 608.2h: its
                            // power is read now, from the live board, and only a permanent that has left
                            // falls back on the power it entered with (the trigger's linked amount).
                            val golem =
                                context.subject
                                    ?: error(
                                        "CR 603.6a: an enters-the-battlefield trigger carries the entered " +
                                            "object as its subject",
                                    )
                            gainLife(state, context.controller, powerOfOrLastKnown(state, golem, context.amount))
                        },
                ),
            )
    }

/**
 * Maelstrom Colossus — `{8}` Artifact Creature — Golem 7/7. "Cascade (When you cast this spell, exile
 * cards from the top of your library until you exile a nonland card that costs less. You may cast it
 * without paying its mana cost. Put the exiled cards on the bottom in a random order.)"
 *
 * Monster Tron's payoff: the deck's whole plan is to reach eight mana early, and at eight mana this is a
 * 7/7 body **plus** a free spell off the top of a deck whose cheapest cards are its best ones. The body
 * is a vanilla artifact creature; the card is the keyword.
 *
 * **Nothing here declares any of that machinery, which is the point.** [SpellDefinition.cascade] is a
 * bare `true` because CR 702.85a spells the whole ability out with no blank for a card to fill in — every
 * number in it ("lesser mana value", "the top of your library") is read off the cascading spell and its
 * controller. `mtg-rules` synthesizes the cast trigger, digs, offers the free cast through
 * [dev.mtgplay.core.definition.CastingPermission.Cascade], and bottoms the rest through the match PRNG;
 * see `Cascade.kt` for all four halves.
 *
 * **The seeded shuffle is the printed line most easily lost.** "Put the exiled cards on the bottom in a
 * random order" is not flavour — the cards cascade turned up and did not cast are cards the controller
 * has *seen*, and putting them back in a known order would hand them a stacked deck for the rest of the
 * game. It draws from [dev.mtgplay.core.random.Rng] (ADR-006), so a replay of the same seed reproduces
 * the same bottom, and the resulting order appears in no seat view and in no event.
 *
 * **What a cascade actually hits here is `{7}` or less**, which in Monster Tron is everything: the deck
 * plays no card more expensive than the Colossus itself, so the dig stops at the first nonland card it
 * meets. That is also why the "may" in "you may cast it" is a real decision rather than a formality — an
 * eight-mana turn that flips a Bonder's Ornament may prefer to bury it than to spend the trigger.
 */
val maelstromColossus: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Maelstrom Colossus",
                manaCost = ManaCost.parse("{8}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Golem")),
                powerToughness = PrintedPowerToughness(power = 7, toughness = 7),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 702.85a: the keyword in full, with nothing for the card to vary.
        override val cascade = true
    }
