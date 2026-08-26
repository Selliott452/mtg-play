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
import dev.mtgplay.core.definition.ManaAbilityRider
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.effect.dealDamage
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
 * `{G}` *per Elf on the battlefield* — the pool's first variable-amount production (CR 605.2).
 *
 * `FW-COUNTERS` unblocked one of the two siblings `FW-MANA` had to leave out. [overgrownBattlement]
 * counts creatures **with defender**, and the only thing missing was the keyword: `Keyword.DEFENDER`
 * now exists with its CR 702.3b rules effect, and [PermanentFilter] grew the card-type and keyword
 * axes the count needs.
 *
 * `FW-MANACOST` then unblocked the other. Saruli Caretaker's "{T}, Tap an untapped creature you
 * control" was the activation cost shape that did not exist, and it lives in CostedManaSources.kt
 * beside the two other cards whose mana abilities cost something — filed by the *problem* they pose
 * (payment capacity, docs/design/mana-payment.md §11) rather than by the creature type they share
 * with these two.
 *
 * `W8-B` adds the two creature mana sources that are not "{T}: Add one mana" at all, and each brings
 * a piece of core vocabulary the file's earlier residents did not need:
 *
 * - [elvesOfDeepShadow] — "{T}: Add {B}. **This creature deals 1 damage to you.**" A mana ability
 *   with a non-mana **rider** ([dev.mtgplay.core.definition.ManaAbilityRider]). CR 605.1a still makes
 *   it a mana ability — it does not target, it could add mana, it is not a loyalty ability — so it
 *   stays stackless and stays in the payment planner. Demoting it to an ordinary activated ability to
 *   get the rider would have deleted the card.
 * - [burningTreeEmissary] — "When this creature enters, add {R}{G}." Not a mana ability at all
 *   (CR 605.1b wants a trigger off a *mana ability*, not off entering the battlefield), so it uses the
 *   stack and the mana arrives in the priority window its resolution hands back. Declared as
 *   [dev.mtgplay.core.definition.TriggeredAbility.addsMana] rather than written into a lambda, so the
 *   acceptance module's floating-mana invariant can see which decks may legitimately hold mana at a
 *   pause. Its `{R/G}{R/G}` is the pool's second hybrid cost after Slippery Bogle's, and the first on a
 *   card whose two halves are the *same* pair of colours twice.
 *
 * `W9-F` adds [tinderWall], which this header had recorded as out for two waves — and the recorded
 * diagnosis was **right in every particular**, which is worth saying because most of the diagnoses this
 * wave re-checked were not. Its ritual half was expressible from the day
 * [ManaAbilityCost.SacrificeSelf] landed; its second ability, "{R}, Sacrifice this creature: It deals 2
 * damage to target creature **it's blocking**", needed exactly the two things the note named:
 *
 * - a **targeting restriction stated relative to the ability's source object**, which is
 *   [TargetSpec.CreatureBlockedBySource] — a [TargetSpec] member rather than a
 *   [dev.mtgplay.core.definition.PermanentRestriction], because every member of that enum answers a
 *   question about the *candidate* and this one asks about the Wall; and
 * - an **ability-LKI capture**, because the sacrifice cost has already made the source a new object in
 *   a graveyard (CR 400.7) before the ability reaches the stack.
 *   [dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack.blockingAtActivation] holds the
 *   *relation* — an id would name a dead object, which is the same reason `Chooser.Ability` carries no
 *   id — captured at CR 601.2c, before CR 601.2h pays the cost, and read again at CR 608.2b.
 *
 * Encoding only the ritual half would have handed an agent a card that cannot do the thing it is held
 * up for, so it was both halves or neither.
 */

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the
 * rules engine moves it from the stack onto the battlefield. Both mana Elves' printed work is
 * their mana ability, never a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** Overgrown Battlement's printed toughness (CR 208.2) — a 0/4 Wall. */
private const val OVERGROWN_BATTLEMENT_TOUGHNESS: Int = 4

/** The damage Elves of Deep Shadow's mana ability deals to its controller (CR 120.3a). */
private const val ELVES_OF_DEEP_SHADOW_DAMAGE: Int = 1

/** Burning-Tree Emissary's printed power and toughness (CR 208.2) — a 2/2. */
private const val BURNING_TREE_EMISSARY_SIZE: Int = 2

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

/**
 * Overgrown Battlement — `{1}{G}` Creature — Wall, a 0/4 with defender and "`{T}`: Add `{G}` for
 * each creature you control with defender." Spy Combo's ramp Wall, and the pool's second
 * variable-amount mana source (CR 605.2).
 *
 * `FW-MANA` had the counting machinery and still could not encode this card: the count reads a
 * *keyword*, and [dev.mtgplay.core.card.Keyword.DEFENDER] did not exist. `FW-COUNTERS` added the
 * keyword with its real CR 702.3b effect, which is what makes the card honest in both halves — the
 * Wall genuinely cannot attack, and it genuinely counts itself.
 *
 * Three things about the oracle text are load-bearing:
 *
 * - **"you control", unlike Priest of Titania's "on the battlefield".** An opposing Wall of Junk
 *   adds nothing here. [PermanentFilter.controlledByYou] is `true`.
 * - **"creature … with defender", two conjuncts, not one.** The filter carries a card type *and* a
 *   keyword; a hypothetical noncreature permanent with defender would not be counted. The keyword
 *   half is read through the CR 613 layer system, so a granted defender counts and a printed one
 *   that some effect removed would not.
 * - **It counts itself**, so a lone Battlement adds exactly one `{G}` and the count is never zero
 *   while the source is on the battlefield to be tapped.
 *
 * Being an activated ability with `{T}` in its cost on a creature, it adds nothing while the
 * Battlement is summoning sick (CR 302.6) — the Wall has defender, not haste.
 */
val overgrownBattlement: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Overgrown Battlement",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Wall")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = OVERGROWN_BATTLEMENT_TOUGHNESS),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.GREEN),
                    amount =
                        ManaAmount.PerPermanent(
                            PermanentFilter(
                                controlledByYou = true,
                                cardType = CardType.CREATURE,
                                keyword = Keyword.DEFENDER,
                            ),
                        ),
                ),
            )
    }

/**
 * Elves of Deep Shadow — `{G}` Creature — Elf Druid, a 1/1 with "`{T}`: Add `{B}`. This creature deals
 * 1 damage to you."
 *
 * The pool's first mana ability with a **rider** (CR 605.1a). Four things about the printed line are
 * load-bearing, and three of them are ways to get the card silently wrong:
 *
 * - **It is still a mana ability**, so it never uses the stack (CR 605.3a), it resolves inside
 *   CR 601.2g in the middle of paying another cost, and an opponent gets no window to respond to it.
 *   CR 605.1a's test is that the ability does not require a target, could add mana as it resolves, and
 *   is not a loyalty ability — all three hold, and none of them is about how much *else* the ability
 *   says. Encoding it as an [dev.mtgplay.core.definition.ActivatedAbility] to get the damage would put
 *   a mana ability on the stack and remove the Elf from the payment planner entirely.
 * - **It taps for `{B}`, not `{G}`.** A green Elf that produces black is the whole point of the card
 *   in Spy Combo, whose black comes from almost nowhere else.
 * - **The damage is dealt by the creature to *you*** — its controller, who is also the activator.
 *   Damage, not life loss: it has a source (CR 120.1), so CR 615 prevention and CR 702.16e protection
 *   both apply to it, which they would not to a bare life subtraction.
 * - **Nothing gates the activation on surviving it.** A player at 1 life may tap the Elf, go to 0, and
 *   lose to CR 704.5a at the next state-based-action check (CR 704.3). Refusing to enumerate that plan
 *   would remove a legal line of play, which ADR-005 makes a defect rather than a mercy — sometimes
 *   the mana wins the game first.
 *
 * Being an activated ability with `{T}` in its cost on a creature, it adds nothing while the Elf is
 * summoning sick (CR 302.6, the `P-MANASICK` gate).
 */
val elvesOfDeepShadow: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Elves of Deep Shadow",
                manaCost = ManaCost.parse("{G}"),
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
                    options = persistentListOf(ManaType.BLACK),
                    rider = ManaAbilityRider.DamageToController(ELVES_OF_DEEP_SHADOW_DAMAGE),
                ),
            )
    }

/**
 * Burning-Tree Emissary — `{R/G}{R/G}` Creature — Human Shaman, a 2/2 with "When this creature enters,
 * add `{R}{G}`."
 *
 * Mono Red Rally's free 2/2, and the pool's first triggered ability that **adds mana without being a
 * mana ability**. Three readings of the printed line decide whether the card works at all:
 *
 * - **It is not a CR 605.1b triggered mana ability.** That rule wants an ability that triggers off the
 *   activation or resolution of a *mana ability*; this triggers off a permanent entering the
 *   battlefield (CR 603.2). So it is an ordinary triggered ability: it goes on the stack, it can be
 *   responded to, and — because CR 603.3 makes it independent of its source — killing the Emissary in
 *   response does not stop the `{R}{G}` arriving. Encoding it as a
 *   [dev.mtgplay.core.definition.TriggeredManaAbility] would make it stackless and unrespondable,
 *   which is a different card.
 * - **The mana must survive into the priority window the resolution hands back**, or the card does
 *   nothing at all. It does: mana empties at the *end of each step and phase* (CR 500.4), not when
 *   priority changes hands, so the `{R}{G}` sits in the pool for the rest of the main phase and casts
 *   the next Emissary. That is the whole reason the card is played.
 * - **`{R/G}{R/G}` is two hybrid symbols, not one and not `{R}{G}`** (CR 107.4). Each is independently
 *   payable with red *or* green, so an Emissary's own `{R}{G}` casts the next one either way round, and
 *   the card is both red and green (CR 202.2) — which matters for a colour-gated cost reduction like
 *   [sunscapeFamiliar]'s and for the Blasts.
 *
 * Its mana is declared ([dev.mtgplay.core.definition.TriggeredAbility.addsMana]) rather than added by
 * its [dev.mtgplay.core.definition.ResolutionEffect], so the engine — and the acceptance module's
 * floating-mana invariant — can see from the definition alone that this deck may hold mana at a pause.
 */
val burningTreeEmissary: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Burning-Tree Emissary",
                manaCost = ManaCost.parse("{R/G}{R/G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Shaman")),
                powerToughness =
                    PrintedPowerToughness(
                        power = BURNING_TREE_EMISSARY_SIZE,
                        toughness = BURNING_TREE_EMISSARY_SIZE,
                    ),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 608.2c: the whole instruction is the mana, and the engine performs it from the
                    // declaration beside it. There is nothing left for an effect to do.
                    effect = entersTheBattlefield,
                    addsMana = persistentListOf(ManaType.RED, ManaType.GREEN),
                ),
            )
    }

/** Tinder Wall's printed toughness (CR 208.2) — a 0/3 Wall. */
private const val TINDER_WALL_TOUGHNESS: Int = 3

/** The red mana Tinder Wall's ritual ability adds (CR 605.2). */
private const val TINDER_WALL_MANA: Int = 2

/** The damage Tinder Wall's second ability deals to the creature it was blocking (CR 120.3d). */
private const val TINDER_WALL_DAMAGE: Int = 2

/**
 * Tinder Wall — `{G}` Creature — Plant Wall, a 0/3 with defender, "Sacrifice this creature: Add
 * `{R}{R}`" and "`{R}`, Sacrifice this creature: It deals 2 damage to target creature it's blocking."
 *
 * Added by `W9-F`, and the reason [TargetSpec.CreatureBlockedBySource] and the ability-LKI capture on
 * [dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack.blockingAtActivation] exist. This file's
 * header recorded the card as blocked for two waves on exactly that, and the diagnosis was right.
 *
 * **Both halves or nothing.** The ritual is a green one-drop that ramps into a three-drop, and it was
 * expressible from the day [ManaAbilityCost.SacrificeSelf] landed. The blocking half is what makes the
 * Wall a *combat* card — a 0/3 that eats a two-power attacker for `{R}` — and encoding only the ritual
 * would have handed an agent a card that cannot do the thing it is played for (PLAN.md §7).
 *
 * Four readings decide whether the second ability is the card:
 *
 * - **"It", the source, deals the damage** (CR 120.1), so the damage has a source with characteristics:
 *   CR 615 prevention and CR 702.16e protection both apply to it, as they would not to a bare life
 *   subtraction. The source is read from the ability's captured
 *   [dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack.sourceCard] (CR 113.7c), the Wall itself
 *   being in a graveyard by then.
 * - **"Target creature it's blocking"** is a restriction on the *source*, not on the candidate, which is
 *   why it is a [TargetSpec] member rather than a
 *   [dev.mtgplay.core.definition.PermanentRestriction]: every member of that enum answers a question
 *   about the permanent being offered, and this one asks about the Wall.
 * - **The relation is last-known information** (CR 113.7c, CR 608.2h). The sacrifice is a *cost*, paid
 *   at CR 601.2h — after the target is chosen at CR 601.2c — so the Wall is still blocking when the
 *   choice is enumerated and is a new object in a graveyard (CR 400.7) by the CR 608.2b re-check.
 *   Re-deriving "it's blocking" from the live combat state there would fizzle every activation.
 * - **The ability cannot be activated outside combat at all** (CR 601.2c): with no block declared there
 *   is no legal target, so the whole activation is absent from the enumerated action space rather than
 *   offered and then wasted. The ritual stays offered, which is the choice the card actually poses —
 *   ramp now, or hold the Wall for a blocker and a Shock.
 *
 * Defender (CR 702.3b) is printed and honoured: the Wall never attacks, which is the only way it is
 * ever *blocking* anything and therefore a precondition of its own second ability.
 */
val tinderWall: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Tinder Wall",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Plant"), Subtype("Wall")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = TINDER_WALL_TOUGHNESS),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 605.1a: no target, could add mana, not a loyalty ability — so it is a mana ability and
        // never uses the stack, even though its cost destroys its own source.
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = persistentListOf(ManaType.RED),
                    cost = persistentListOf(ManaAbilityCost.SacrificeSelf),
                    amount = ManaAmount.Fixed(TINDER_WALL_MANA),
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 602.1: printed order — the `{R}` is paid before the sacrifice.
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{R}")),
                            AbilityCost.SacrificeSelf,
                        ),
                    // CR 115.1b/509.1: the attacker this Wall was blocking as the ability was activated.
                    targetSpec = TargetSpec.CreatureBlockedBySource,
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamage(
                                state,
                                context.damageSource(),
                                context.targets.single(),
                                TINDER_WALL_DAMAGE,
                            )
                        },
                ),
            )
    }
