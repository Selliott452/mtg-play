package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.destroy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's one **activated ability with a variable cost** (`W9-C`).
 *
 * `FW-X` gave the engine CR 107.3's announcement for *spells*. Gorilla Shaman is the card that needed it
 * for an *ability*, and `W8-C` dropped it for two reasons that were both accurate at the time: no
 * `PendingActivation.chosenX` existed, and — the interesting half — the ability's **target restriction is a
 * function of the announced value**, which forces the announcement above CR 601.2c. This packet added the
 * stage on the activation path at CR 601.2b's printed position and left the cast path's deviation alone;
 * `mtg-rules/AbilityXCost.kt` argues that choice and `docs/design/dependent-targets.md` §3 records it.
 *
 * **The prompt for this packet said the cost is `{X}{X}`. The oracle text says `{X}{X}{1}`**, and the
 * oracle text wins (the repo's own Scryfall snapshot). The difference is not cosmetic: at X = 2 the printed
 * ability costs five mana, not four, and a four-mana encoding would hand the deck a Mox-eating line a turn
 * early.
 */

/** A permanent spell's resolution puts it onto the battlefield and does nothing else (CR 608.3). */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** The two subtypes Gorilla Shaman prints (CR 205.3m). */
private val APE_SHAMAN = persistentSetOf(Subtype("Ape"), Subtype("Shaman"))

/**
 * Gorilla Shaman — `{R}` Creature — Ape Shaman 1/1.
 * "{X}{X}{1}: Destroy target noncreature artifact with mana value X."
 *
 * **The cost is `{X}{X}{1}`, so an announced X of *n* is paid with 2n + 1 mana** — the doubled symbol is
 * why the card is priced as a slow, repeatable answer rather than a cheap one. `ManaCost.substitutingX`
 * replaces *every* occurrence of the symbol, so the engine charges `{2}{2}{1}` for X = 2 without the card
 * saying anything; encoding the cost as "{2X}{1}" is not a thing Magic prints and would not be this card.
 *
 * **Its target restriction reads the ability's own announced X, and that is the whole reason the card was
 * blocked.** [PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X] compares the *candidate's*
 * printed mana value (CR 202.3b) with the *ability's* announced X (CR 107.3) — two different quantities
 * that happen to share a letter. The engine therefore has to announce X **before** it enumerates targets
 * (CR 601.2b then CR 601.2c), which is the printed order and which the activation path now follows.
 *
 * **Only payable-and-targetable values of X are offered** (ADR-005). Announcing X = 4 with nothing but
 * `{0}` artifacts on the board would reach the target stage with an empty option list, and an activation
 * cannot be abandoned once begun — so `abilityXValueOptions` tests both halves for each candidate and the
 * ability is not enumerated at all when no single value carries the whole activation.
 *
 * **"Noncreature" is load-bearing in this gauntlet, not filler.** Ornithopter and the Bauble artifacts are
 * mana-value-0 artifacts, and Memnite-style artifact *creatures* are exactly what the exclusion keeps off
 * the option list: an artifact creature is a creature (CR 205.1a) and is never a legal choice however
 * cheap it is. The far more common target is a mana-value-0 artifact land or a Bauble at X = 0, which the
 * printed cost buys for one mana.
 *
 * **A `{T}`-free ability, so summoning sickness does not apply** (CR 302.6 restricts only abilities with
 * `{T}` or `{Q}` in their cost). Gorilla Shaman can eat an artifact the turn it lands, given the mana —
 * which is the printed card and a real line of play, so the engine must enumerate it.
 *
 * The creature spell itself is an ordinary sorcery-speed cast that targets nothing; the ability targets.
 */
val gorillaShaman: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gorilla Shaman",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = APE_SHAMAN,
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        // CR 302.1: a creature spell is cast at sorcery speed.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 107.3: {X} appears twice, so the announced value is charged twice over.
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{X}{X}{1}"))),
                    targetSpec =
                        TargetSpec.TargetPermanent(PermanentRestriction.NONCREATURE_ARTIFACT_WITH_MANA_VALUE_X),
                    effect =
                        ResolutionEffect { state, context ->
                            val target =
                                context.targets.singleOrNull() as? Target.Permanent
                                    ?: error(
                                        "CR 115.1b: Gorilla Shaman's ability targets one permanent, " +
                                            "got ${context.targets}",
                                    )
                            // CR 701.7a: the CR 608.2b re-check has already confirmed the target is still
                            // a noncreature artifact of the announced mana value, so this is a plain
                            // destroy — the restriction is not re-read here.
                            destroy(state, target.id)
                        },
                ),
            )
    }
