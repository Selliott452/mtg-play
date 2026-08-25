package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
import dev.mtgplay.rules.effect.dealDamage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's first mana sources whose ability costs something other than `{T}` or "sacrifice this"
 * (`FW-MANACOST`, docs/design/mana-payment.md §11) — the cards that made [ManaAbilityCost] necessary.
 *
 * Each is here because it exercises a *different* half of the capacity problem, and between them they
 * are the framework's witnesses:
 *
 * - [saruliCaretaker] — "{T}, Tap an untapped creature you control". The cost consumes a resource that
 *   is not the source's own class membership, so one class's activation can spend another's.
 * - [wallOfRoots] — "Put a -0/-1 counter on this creature". The cost neither taps nor removes the
 *   source, so nothing about the battlefield bounds how often it could be activated; only the CR 602.5b
 *   "Activate only once each turn" restriction does.
 * - [barrelsOfBlastingJelly] — "{1}: Add one mana of any color", once each turn. The cost is *mana*, so
 *   the activation is a consumer as well as a producer: the enumerator has to net it out of coverage,
 *   prove an execution order exists, and stop the activation funding itself.
 *
 * The wave's other four costed sources are absent, each for a framework this packet does not own; the
 * packet report lists them.
 */

/** The damage Barrels of Blasting Jelly's sacrifice ability deals (CR 120.3a). */
private const val BARRELS_DAMAGE: Int = 5

/** Saruli Caretaker's printed toughness (CR 208.2) — a 0/3 Dryad. */
private const val SARULI_CARETAKER_TOUGHNESS: Int = 3

/** Wall of Roots' printed toughness (CR 208.2) — a 0/5 Plant Wall. */
private const val WALL_OF_ROOTS_TOUGHNESS: Int = 5

/** The five colours an "add one mana of any color" ability offers, in WUBRG order (CR 105.1). */
private val ANY_COLOR =
    persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)

/**
 * CR 608.3: a permanent spell with no instructions of its own resolves by entering the battlefield.
 * All three cards' printed work is on the permanent, never on the spell.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Saruli Caretaker — `{G}` Creature — Dryad. "Defender. {T}, Tap an untapped creature you control: Add
 * one mana of any color."
 *
 * **The card `FW-MANA` refused to approximate** (docs/design/mana-payment.md §9 named it), and the
 * reason is the second cost component: tapping another creature is a payment-*capacity* problem, not a
 * production one. Two Caretakers and no other creature can produce exactly one mana between them —
 * each needs itself untapped for the `{T}` and a *second* untapped creature for the other component —
 * and no amount of per-class capacity counting sees that, because the resource being consumed belongs
 * to no class in particular. What sees it is the shared untapped-creature budget of §11.3.
 *
 * Two rules subtleties the encoding depends on:
 *
 * - **The tapped creature need not be untapped-and-settled.** The tap symbol appears on the
 *   Caretaker, not on the creature being tapped, so CR 602.5a does not reach it: a creature played
 *   this very turn is a legal choice, exactly as it is for Springleaf Drum.
 * - **It cannot tap itself.** The `{T}` component has already tapped it by the time the second
 *   component is paid, and "an untapped creature you control" is then false of it. The engine's
 *   helper-creature search excludes the source outright rather than relying on the ordering.
 *
 * Defender (CR 702.3b) is printed and carried as a keyword; it is also what makes the Caretaker a
 * legitimate [overgrownBattlement] count, which is a synergy the Elves/Walls lists actually run.
 */
val saruliCaretaker: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Saruli Caretaker",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Dryad")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = SARULI_CARETAKER_TOUGHNESS),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = ANY_COLOR,
                    // CR 602.1: printed order — the {T} taps the Caretaker, then a second creature.
                    cost = persistentListOf(ManaAbilityCost.TapSelf, ManaAbilityCost.TapAnotherCreature),
                ),
            )
    }

/**
 * Wall of Roots — `{1}{G}` Creature — Plant Wall. "Defender. Put a -0/-1 counter on this creature: Add
 * {G}. Activate only once each turn."
 *
 * **The card whose cost does not touch the battlefield at all.** Every mana source before it either
 * tapped or sacrificed itself, and that is what made "one activation per class member" a bound: an
 * activated member stopped being available. A Wall of Roots is exactly as available after being
 * activated as before, so nothing in the capacity model bounds it — the CR 602.5b restriction is the
 * whole bound, which is why [ManaAbility] refuses at construction to accept an ability that neither
 * consumes its source nor carries the restriction.
 *
 * Three consequences worth stating, all of which follow and none of which is special-cased:
 *
 * - It taps for mana **while tapped**, because its cost has no `{T}`; blocking with it and then
 *   using it is a real line the enumerator now offers.
 * - It taps for mana while **summoning sick**, because CR 602.5a restricts `{T}` and `{Q}` costs only.
 * - It taps for mana on the **opponent's** turn, and again on your own, because CR 500.1 makes "each
 *   turn" mean each player's turn — the reset is per turn, not per round.
 *
 * The `-0/-1` counter (CR 122.1a) has existed since `FW-COUNTERS`, which modelled it and then recorded
 * that no card could place one yet; this is that card. Five activations kill it as the CR 704.5f
 * zero-toughness state-based action, which needs no extra machinery: the counter is a layer-7c
 * modifier like any other.
 */
val wallOfRoots: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Wall of Roots",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Plant"), Subtype("Wall")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = WALL_OF_ROOTS_TOUGHNESS),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.GREEN),
                    cost = persistentListOf(ManaAbilityCost.PutCounterOnSelf(Counter.MINUS_ZERO_MINUS_ONE)),
                    oncePerTurn = true,
                ),
            )
    }

/**
 * Barrels of Blasting Jelly — `{1}` Artifact. "{1}: Add one mana of any color. Activate only once each
 * turn. {5}, {T}, Sacrifice this artifact: It deals 5 damage to target creature."
 *
 * **The acyclicity witness.** Its mana ability costs mana, so an activation both consumes and
 * produces, and the two questions that follow have no pre-`FW-MANACOST` answer:
 *
 * 1. *Which* mana pays the `{1}`? Converting a green into a red and converting a red into a green are
 *    different lines from the same board, so the plan records the choice
 *    ([dev.mtgplay.rules.decision.ManaActivation.costPayment]) rather than leaving it to the executor.
 * 2. In what **order** do the activations run? Two Barrels on an empty pool can pay for each other on
 *    paper — two mana produced against two mana of cost — and cannot in fact, because neither can go
 *    first. The enumerator refuses that plan by deriving an execution order and finding none
 *    (docs/design/mana-payment.md §11.2).
 *
 * The oracle text is also where the gauntlet triage was wrong: it files the ability under "`{N}`,
 * `{T}`", and the card prints **no tap symbol**. That matters — a tapped Barrels still filters mana,
 * and the same Barrels can be sacrificed to its second ability in the same turn it filtered.
 *
 * The second ability is an ordinary CR 602 activated ability with a mana component, a `{T}`, a
 * self-sacrifice and a target: the T17 reservation (docs/design/mana-payment.md §2.2) keeps the
 * Barrels itself out of the plans offered for that `{5}`, because the `{T}` component needs it
 * untapped.
 */
val barrelsOfBlastingJelly: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Barrels of Blasting Jelly",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = ANY_COLOR,
                    // No {T}: the printed cost is the bare {1}. CR 602.5b is what bounds it.
                    cost = persistentListOf(ManaAbilityCost.Mana(ManaCost.parse("{1}"))),
                    oncePerTurn = true,
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 602.1: printed order — mana, then the tap, then the sacrifice.
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{5}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamage(state, context.targets.single(), BARRELS_DAMAGE)
                        },
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                ),
            )
    }
