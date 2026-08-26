package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AlternativeFace
import dev.mtgplay.core.definition.FaceKind
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.dealDamageToEachPermanent
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.isCreaturePermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The two cards in the gauntlet with **two faces printed on one piece of cardboard** — an adventurer
 * card (CR 715) and an omen card (CR 720). Both were deferred through waves 8 and 9 on a diagnosis that
 * was accurate and is now discharged: *"`CastingPermission` could carry the permission;
 * `PrintedCharacteristics` cannot carry two faces, and that is the real blocker"*
 * (docs/gauntlet-deferred-ten.md, restated in AlternateCastings.kt).
 *
 * **What the framework turned out to be.** A face is a whole second [SpellDefinition] hanging off the
 * card's own ([SpellDefinition.alternativeFace]), and casting as a face substitutes it for the card's
 * for the whole of the CR 601 pipeline (`CardFaces.kt` in `mtg-rules`). That is the one seam, and
 * everything the diagnosis feared would "reach every card in the pool" did not happen:
 * [dev.mtgplay.core.identity.CardRef] stays the card's name in every zone (CR 715.2c, CR 720.2c — an
 * adventurer card is *one* card), no CR 400.7 zone move has to decide which key an object carries, and
 * [PrintedCharacteristics] grew nothing at all.
 *
 * **Why a face is not a [dev.mtgplay.core.definition.SpellMode] and not a
 * [dev.mtgplay.core.definition.CastingPermission.Prototype].** A mode is chosen *within* one cast at
 * CR 601.2b, after the spell is on the stack at one cost; a face is chosen *before* the cast is
 * enumerated, at a different cost, and puts a spell of a different **card type** on the stack. Prototype
 * changes what a spell *is* but keeps its name, its types and all of its text (CR 718.3b); a face keeps
 * none of those (CR 715.3b: the spell has **only** its alternative characteristics), so its instructions
 * needed somewhere to live and `SpellDefinition.resolution` is one effect.
 *
 * **The two mechanics differ in exactly one clause, and it is the interesting one.** An Adventure that
 * resolves is **exiled**, and its controller may play the card's normal half from exile afterwards
 * (CR 715.3d) — so Fang Dragon banks a 6/3 flier behind a two-mana sweeper. An Omen that resolves is
 * **shuffled into its owner's library** (CR 720.3d) — so Sagu Wildling spends the creature to find a
 * land and may draw it again later. Both are enumerated as options beside the ordinary cast, never
 * instead of it (ADR-005): a seat holding either card with enough mana sees both halves.
 *
 * **The Omen's shuffle consumes seeded entropy** (ADR-006). Both of Sagu Wilds' shuffles do — the search
 * clause's "then shuffle" and CR 720.3d's own "(Also shuffle this card.)" — and both draw from the
 * match-owned [dev.mtgplay.core.random.Rng], in that order, so a replay of the same seed reproduces the
 * same library.
 *
 * **A note on the face names.** The repo's Scryfall snapshot flattens a two-faced card into one entry
 * and drops the inset frame's name: Forktail Sweep survives only because its own rules text says it,
 * and Sagu Wilds is not in the snapshot at all. Both are printed on the physical cards and are carried
 * here as the face's CR 201 name. Nothing in the engine keys on them — the registry key is the card's
 * name (CR 715.2c) — so they are identity for a seat to read (CR 405: the stack is public) rather than
 * a rules fact anything computes with.
 */

/** The damage Forktail Sweep deals to each creature its caster does not control (CR 120). */
const val FORKTAIL_SWEEP_DAMAGE: Int = 1

/** The life Sagu Wildling's enters-the-battlefield trigger gains (CR 119.3). */
const val SAGU_WILDLING_LIFEGAIN: Int = 3

/** The Dragon creature type both cards print (CR 205.3m). */
private val DRAGON: Subtype = Subtype("Dragon")

/** The Adventure spell type (CR 205.3k) — the subtype an adventurer card's inset frame prints. */
private val ADVENTURE: Subtype = Subtype("Adventure")

/** The Omen spell type (CR 205.3k) — the subtype an omen card's inset frame prints. */
private val OMEN: Subtype = Subtype("Omen")

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. Both normal halves in this file do their printed
 * work through a keyword or a triggered ability, never through a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * *Forktail Sweep* — `{1}{R}` Sorcery — Adventure. "Forktail Sweep deals 1 damage to each creature you
 * don't control."
 *
 * Fang Dragon's inset frame (CR 715.2), and a whole card in its own right: a two-mana one-sided sweeper
 * that answers an Elves board or a pack of Spy Combo Thopters. Nothing about it is a modification of the
 * Dragon — it has a different name, a different cost, a different card type and its own instructions,
 * which is exactly why the framework had to be a second definition rather than a bag of overrides.
 *
 * **One-sided, and the qualifier is the card.** [dealDamageToEachPermanent]'s affected set is fixed once
 * as the effect begins (CR 608.2) and here excludes the caster's own creatures — control is ownership in
 * the MVP pool. Dropping the "you don't control" half would turn a tempo play into a symmetric sweeper
 * that kills the caster's own board, and Fang Dragon is a Spy Combo card whose board is full of tokens:
 * the wrong version is not merely weaker, it is unplayable in the deck that plays it.
 *
 * The damage is *marked* (CR 120.3d) and nothing dies during resolution; the lethal-damage state-based
 * action (CR 704.5g) acts at the next check, after the sorcery has finished leaving the stack.
 *
 * **The exile is not written here.** "(Then exile this card. You may cast the creature later from
 * exile.)" is reminder text for CR 715.3d, which is a rule about every Adventure rather than a printed
 * instruction of this one — so the engine performs it as the spell leaves the stack, marks the exiled
 * card [dev.mtgplay.core.state.GameObject.onAnAdventure], and enumerates the *creature* half from exile
 * thereafter. A Forktail Sweep that is countered or that resolves with nothing to damage still goes on
 * its adventure; one that is countered does not (CR 715.3d replaces only the move made *as it resolves*).
 */
val forktailSweep: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Forktail Sweep",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(ADVENTURE),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamageToEachPermanent(state, context.damageSource(), FORKTAIL_SWEEP_DAMAGE) { current, obj ->
                    // CR 120: "each creature you don't control" — control is ownership in the MVP pool.
                    isCreaturePermanent(current, obj) && obj.owner != context.controller
                }
            }
    }

/**
 * Fang Dragon — `{5}{R}{R} // {1}{R}` Creature — Dragon // Sorcery — Adventure, a 6/3. "Flying //
 * Forktail Sweep deals 1 damage to each creature you don't control. (Then exile this card. You may cast
 * the creature later from exile.)"
 *
 * Spy Combo's sideboard Dragon, and the pool's **first adventurer card** (CR 715). Two spells one card
 * long: a two-mana sweeper now, or a seven-mana 6/3 flier, or — the line the mechanic exists for — the
 * sweeper now and the flier later off the same card.
 *
 * **All three lines are enumerated** (ADR-005), and the third is the one an approximation would have
 * deleted. A seat holding this card with two mana sees the Adventure; with seven, both halves; with the
 * card sitting in exile after a Forktail Sweep resolved, the creature at its printed `{5}{R}{R}` — and
 * *only* the creature, because CR 715.3d says an adventurer card played from that exile "can't be cast
 * as an Adventure this way". Encoding just the creature would have made this a vanilla seven-mana 6/3,
 * which is not the card; encoding just the Adventure would have made it a sorcery. Either is the
 * plausible-looking wrong card PLAN.md §7 refuses.
 *
 * The normal half is printed vocabulary throughout: [Keyword.FLYING] (CR 702.9) on a 6/3 Dragon body,
 * with no ability of its own. Everything two-faced about it is the one declaration below.
 */
val fangDragon: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fang Dragon",
                manaCost = ManaCost.parse("{5}{R}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(DRAGON),
                powerToughness = PrintedPowerToughness(power = 6, toughness = 3),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 715.2: the inset frame. The engine synthesizes the CastingPermission that offers it from
        // this declaration (CardFaces.kt), so the card states the face once and cannot restate it wrongly.
        override val alternativeFace = AlternativeFace(FaceKind.ADVENTURE, forktailSweep)
    }

/**
 * *Sagu Wilds* — `{G}` Sorcery — Omen. "Search your library for a basic land card, reveal it, put it
 * into your hand, then shuffle. (Also shuffle this card.)"
 *
 * Sagu Wildling's inset frame (CR 720.2): a one-mana Lay of the Land that puts the Dragon back in the
 * deck rather than in the graveyard.
 *
 * **Two shuffles, both seeded, in printed order** (ADR-006). The first is the search's own "then
 * shuffle" (CR 701.18), run by the [LibrarySearch] clause after this resolution's no-op; the second is
 * CR 720.3d's, which the parenthesis is reminder text for and which the engine performs as the spell
 * leaves the stack. Neither is cosmetic: the searcher has just *looked* at their whole library, so a
 * missing shuffle would hand them a known deck, and the omen card going back to a known position would
 * hand them a known draw.
 *
 * **The shuffle-in is not written here** for [forktailSweep]'s reason: CR 720.3d is a rule about every
 * Omen rather than an instruction of this one, so the engine performs it and the definition stays the
 * printed sentence. A countered or fizzled Sagu Wilds goes to the graveyard like anything else — the
 * rule replaces only the move made *as it resolves*.
 *
 * The search itself is published vocabulary: [LibrarySearchFilter.BASIC_LAND_CARD] to
 * [dev.mtgplay.core.definition.LibrarySearchDestination.REVEALED_TO_HAND], the default, which is the
 * "reveal it, put it into your hand" half. It is **mandatory** — the card does not say "you may" — but
 * CR 701.18b's fail-to-find is available as always, and the shuffles happen either way.
 */
val saguWilds: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sagu Wilds",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(OMEN),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // Everything the spell does is its clause, which the engine runs after this no-op.
        override val resolution = ResolutionEffect { state, _ -> state }
        override val librarySearch = LibrarySearch(find = LibrarySearchFilter.BASIC_LAND_CARD)
    }

/**
 * Sagu Wildling — `{4}{G} // {G}` Creature — Dragon // Sorcery — Omen, a 3/3. "Flying. When this
 * creature enters, you gain 3 life. // Search your library for a basic land card, reveal it, put it
 * into your hand, then shuffle. (Also shuffle this card.)"
 *
 * A flex slot in Elves and Spy Combo, and the pool's **first omen card** (CR 720). The cheaper half of
 * the two-faces framework and the one that shows what CR 720 is *for*: a five-mana 3/3 flier is a card
 * you are pleased to draw late and sorry to draw early, and the Omen turns the early copy into a
 * one-mana land that goes back in the deck to be drawn late.
 *
 * **The difference from an Adventure is the whole design of the card, and it is one clause.** CR 720.3d
 * shuffles a resolved Omen into its owner's library instead of putting it into their graveyard, where
 * CR 715.3d would have exiled it and let it be played later. So Sagu Wilds does not *bank* the Dragon
 * the way Forktail Sweep banks Fang Dragon — it spends it for a land and returns it to the deck, which
 * is why an omen card needs no exile marker and no permission from exile at all. Encoding the Omen as an
 * Adventure would produce a card that ramps *and* keeps a 3/3 flier on call: strictly better than
 * printed, in the direction PLAN.md §7 warns about.
 *
 * The normal half is printed vocabulary: [Keyword.FLYING] (CR 702.9) on a 3/3 Dragon, and a
 * [TriggerCondition.EnteredBattlefieldSelf] trigger over [gainLife] (CR 603.6a, CR 119.3). The trigger
 * belongs to the *creature* and fires on no other line — an Omen spell resolving never becomes a
 * permanent, so casting the face gains nothing.
 */
val saguWildling: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sagu Wildling",
                manaCost = ManaCost.parse("{4}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(DRAGON),
                powerToughness = PrintedPowerToughness(power = 3, toughness = 3),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, SAGU_WILDLING_LIFEGAIN)
                        },
                ),
            )

        // CR 720.2: the inset frame; the engine synthesizes the permission that offers it.
        override val alternativeFace = AlternativeFace(FaceKind.OMEN, saguWilds)
    }
