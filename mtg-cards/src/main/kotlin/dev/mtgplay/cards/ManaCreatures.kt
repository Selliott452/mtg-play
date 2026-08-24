package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The pool's first **creature** mana sources (CR 605.1a on a CR 302 permanent): the two `{G}`
 * one-drop mana Elves the gauntlet's Elves list runs.
 *
 * Nothing about the definitions is new — a creature spell with an intrinsic [ManaAbility], both
 * primitives long published (ADR-003). What *is* new is that the mana-payment path now has to
 * answer a question it had never been asked: a land is never summoning sick in any way that
 * matters, so until these two cards existed **CR 302.6 was unreachable from mana payment**, and
 * `PaymentEnumeration.manaSourceClasses` did not check it. It does now, through the shared
 * `manaSourceUsable` predicate: a Mystic played this turn funds no payment plan, exactly as the
 * `{T}` cost component of a non-mana activated ability has always behaved.
 *
 * That gate is the reason these two land together with the fix rather than after it: the failure
 * it prevents is silent and in the agent's favour — mana offered in the enumerated action space
 * that the rules do not permit (PLAN.md §7, docs/gauntlet-card-triage.md §7 T1).
 *
 * The `FW-MANA` packet then adds the list's third Elf, [priestOfTitania], whose ability adds one
 * `{G}` *per Elf on the battlefield* — the pool's first variable-amount production (CR 605.2). Its
 * two would-be siblings from Spy Combo stay absent rather than approximated: Overgrown Battlement
 * counts creatures **with defender**, a keyword `mtg-core` does not have (`FW-DEFENDERKW`), and
 * Saruli Caretaker's ability costs "{T}, Tap an untapped creature you control", an activation cost
 * shape that does not exist and that is a payment-*capacity* problem rather than a production one
 * (docs/design/mana-payment.md §9).
 */

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the
 * rules engine moves it from the stack onto the battlefield. Both mana Elves' printed work is
 * their mana ability, never a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** The creature types both mana Elves print (CR 205.3m). */
private val ELF_DRUID: PersistentSet<Subtype> = persistentSetOf(Subtype("Elf"), Subtype("Druid"))

/**
 * A 1/1 Elf Druid for `{G}` whose whole printed text is the intrinsic mana ability
 * `{T}: Add {G}` (CR 605.1a). A creature spell is cast at sorcery speed and targets nothing
 * (CR 302.1, CR 601.2c); on the battlefield its mana ability resolves immediately, with no stack
 * and no priority round (CR 605.3), and — being an activated ability with `{T}` in its cost on a
 * creature — cannot be activated at all while the creature is summoning sick (CR 302.6).
 */
private fun greenManaElf(name: String): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = ELF_DRUID,
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        // CR 302.1: a creature spell is cast at sorcery speed (it is not an instant).
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities = persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
    }

/**
 * Elvish Mystic — `{G}` Creature — Elf Druid, a 1/1 with "`{T}`: Add `{G}`". The pool's first
 * creature mana source, and therefore the first object for which the CR 302.6 summoning-sickness
 * gate on mana payment is observable.
 */
val elvishMystic: SpellDefinition = greenManaElf("Elvish Mystic")

/**
 * Fyndhorn Elves — `{G}` Creature — Elf Druid, a 1/1 with "`{T}`: Add `{G}`". A functional
 * reprint of [elvishMystic]; the two are nonetheless **distinct payment source classes**, because
 * the payment equivalence relation keys on the printed card as well as the production profile
 * (docs/design/mana-payment.md §2) — a deliberate safety margin, not an oversight.
 */
val fyndhornElves: SpellDefinition = greenManaElf("Fyndhorn Elves")

/**
 * Priest of Titania — `{1}{G}` Creature — Elf Druid, a 1/1 with "`{T}`: Add `{G}` for each Elf on
 * the battlefield."
 *
 * The pool's first **variable-amount** mana source (CR 605.2), and the counted half of `FW-MANA`
 * next to the Urza lands' conditional half. Three things about the oracle text are load-bearing and
 * easy to get wrong:
 *
 * - **"each Elf on the battlefield", not "each Elf you control."** The count includes the opponent's
 *   Elves, which in an Elves mirror is the difference between a Priest adding three and adding six.
 *   [PermanentFilter.controlledByYou] is `false` here and `true` on the Urza lands, and that one
 *   boolean is the whole distinction.
 * - **It counts itself.** A Priest is an Elf, so a lone Priest adds exactly one `{G}` — the count is
 *   never zero while the source is on the battlefield to be tapped, which is why the "a source that
 *   adds nothing is no source" branch of `productionProfile` is unreachable from this card.
 * - **The count is read when the ability resolves** (CR 605.2), not when the cost was locked in
 *   (CR 601.2f). Two Priests paying one cost is two activations, and if something removed an Elf
 *   between them the second would add less — see docs/design/mana-payment.md §8.3 for what the
 *   engine does about that.
 *
 * Being an activated ability with `{T}` in its cost on a creature, it adds nothing at all while the
 * Priest is summoning sick (CR 302.6, the `P-MANASICK` gate).
 */
val priestOfTitania: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Priest of Titania",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = ELF_DRUID,
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.GREEN),
                    amount = ManaAmount.PerPermanent(PermanentFilter(Subtype("Elf"), controlledByYou = false)),
                ),
            )
    }
