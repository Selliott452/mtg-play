package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.engine.countMatchingPermanents
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The pool's first cards whose text creates a continuous effect with a **duration** (CR 611.2,
 * CR 514.2) — `FW-DURATION`, docs/design/duration.md.
 *
 * Every continuous effect the engine could express before this packet came from an Aura's static
 * ability and lasted exactly as long as the Aura stayed attached (docs/design/layer-system.md §2).
 * These are the other generator: a resolving ability creates an effect that outlives it, modifies a
 * permanent it does not touch, and ends in the cleanup step.
 *
 * **The magnitude is snapshotted, and that is the whole difficulty** (CR 608.2h, CR 611.2d). "+X/+X
 * where X is the number of Elves" is counted **once**, when the ability resolves, and the resulting
 * effect is that size for the rest of the turn. It looks exactly like Ethereal Armor's "+1/+1 for
 * each enchantment you control", which is the opposite semantics — a
 * [dev.mtgplay.core.definition.Magnitude.Dynamic] read live on every characteristic computation
 * (CR 613.3c). The engine keeps the two apart at the type level: [applyUntilEndOfTurn] accepts an
 * `Int` and has nowhere to put a state-reading function, so the count happens here, in the
 * resolution, where CR 608.2h says it happens (docs/gauntlet-card-triage.md T16).
 */

/** The creature types Timberwatch Elf prints (CR 205.3m), and the type its own ability counts. */
private val ELF: Subtype = Subtype("Elf")

/**
 * The battlefield permanents Timberwatch Elf counts: **every** Elf, not only its controller's
 * (CR 205.3). `controlledByYou = false` is the whole of that distinction, exactly as on
 * [priestOfTitania] — in an Elves mirror it is the difference between +3/+3 and +6/+6.
 */
private val EVERY_ELF: PermanentFilter = PermanentFilter(subtype = ELF, controlledByYou = false)

private val TIMBERWATCH_ELF: CardRef = CardRef("Timberwatch Elf")

/**
 * Timberwatch Elf — `{2}{G}` Creature — Elf, a 1/2 with
 * "`{T}`: Target creature gets +X/+X until end of turn, where X is the number of Elves on the
 * battlefield."
 *
 * The demonstration card for `FW-DURATION`, and it earns that over Basilisk Gate — which prints the
 * same ability shape — because it needs *nothing* the duration framework does not provide. The Gate
 * additionally needs "Activate only as a sorcery", a timing restriction [ActivatedAbility] cannot
 * express, and it is both a mana source and the source of a `{T}`-costed ability with a mana
 * component, which is trap T17 (docs/gauntlet-card-triage.md). Timberwatch Elf has neither problem:
 * its cost is a bare `{T}` and it produces no mana at all.
 *
 * Four oracle details are load-bearing, and three of them are silent if got wrong:
 *
 * - **"the number of Elves on the battlefield", not "Elves you control."** Both players' Elves count.
 * - **It counts itself.** A lone Timberwatch Elf gives +1/+1, never +0/+0 — which is also why the
 *   effect can never be the empty one [applyUntilEndOfTurn] refuses.
 * - **The count is taken once, on resolution** (CR 608.2h, CR 611.2d). A fourth Elf entering after
 *   the ability resolved does **not** grow the pump. This is T16's failure mode, and it is why the
 *   count runs here rather than being handed to the engine as a function.
 * - **"Target creature"** is [PermanentRestriction.CREATURE] — either player's, with no control
 *   clause — so the Elf can pump an opponent's creature, and hexproof (CR 702.11) is the only thing
 *   that narrows the enumeration.
 *
 * Being an activated ability with `{T}` in its cost on a creature, it cannot be activated at all
 * while the Elf is summoning sick (CR 302.6), which `Activation.kt` already enforces.
 */
val timberwatchElf: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TIMBERWATCH_ELF.name,
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(ELF),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 2),
            )

        // CR 302.1: a creature spell is cast at sorcery speed (it is not an instant).
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 608.2h: X is calculated now, once, and the effect keeps that value.
                            val elves = countMatchingPermanents(state, EVERY_ELF, context.controller)
                            applyUntilEndOfTurn(
                                state = state,
                                affected = targetedPermanent(context.targets),
                                modification = ContinuousModification(powerMod = elves, toughnessMod = elves),
                                sourceCard = TIMBERWATCH_ELF,
                                source = context.source,
                            )
                        },
                ),
            )
    }

/**
 * The single permanent [targets] names (CR 115.1b). Fails loudly on any other shape: the CR 608.2b
 * re-check has already run, so a resolving ability whose spec is a
 * [TargetSpec.TargetPermanent] always holds exactly one legal permanent target (ADR-005), and
 * anything else is an engine defect rather than a rules case.
 */
private fun targetedPermanent(targets: List<Target>): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: ${TIMBERWATCH_ELF.name}'s ability targets exactly one permanent, got $targets")
