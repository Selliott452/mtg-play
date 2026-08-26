package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EachOpponentSacrifices
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeNarrowing
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.effect.gainLife
import dev.mtgplay.rules.effect.putCounters
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **optional additional cost** cards (`FW-BARGAIN`, `W9-B`): "you may [do this] as you
 * cast this spell", where the doing consumes an object the caster picks.
 *
 * [troublemakerOuphe] opened the cost cell [dev.mtgplay.core.definition.OptionalAdditionalCost]
 * describes: optional *and* object-choosing, the one corner of the mandatory/optional by
 * mana/non-mana square the engine did not have. Kicker is optional with nothing to pick; Grab the
 * Prize's discard picks but is mandatory; a [dev.mtgplay.core.definition.CastingPermission] replaces the
 * printed cost instead of adding to it.
 *
 * **`W9-B` added the cell's second member and, with it, the engine's sixth decision shape.** Collect
 * evidence ([vituGhaziInspector], [extractAConfession]) is announced by the same yes/no as bargain and
 * paid at the same CR 601.2b stage, but its answer is bounded by a *summed mana value* rather than by a
 * count — so the two members share the pipeline and part company at the request. See
 * `DecisionRequest.SummedSelection` for why the graveyard is offered as a flat O(n) list rather than as
 * an enumeration of paying subsets, and `OptionalAdditionalCost.CollectEvidence` for why enumerating
 * even the *minimal sufficient* subsets would be both exponential and wrong on the merits.
 *
 * Extract a Confession needed one thing more, and `W9-B` built that too: an **each-opponent-sacrifices**
 * clause ([dev.mtgplay.core.definition.EachOpponentSacrifices]), the second `FW-NONCTRLDEC` member and
 * the first whose option list is *public* — a graveyard-hand asymmetry that turns out to matter less
 * than the other thing it is first at, which is being **narrowed by a linked cost**: "instead each
 * opponent sacrifices a creature with the greatest power among creatures they control" is still a
 * choice whenever two tie, so the narrowing filters the enumeration rather than collapsing it.
 *
 * One further card this packet was offered is **absent**, with a diagnosis below rather than an
 * approximation. Every oracle text here was checked against the repo's own Scryfall snapshot
 * (`mtg-pauper/src/main/resources/scryfall-mvp.json`), which is the authority.
 *
 * ## Absent — Writhing Chrysalis `{2}{R}{G}`
 *
 * Two independent blockers, and **devoid is not one of them** — the packet brief predicted devoid would
 * need a CR 613 layer-5 colour-setting effect, and it does not: [dev.mtgplay.core.card.Keyword.DEVOID]
 * exists and [dev.mtgplay.core.card.PrintedCharacteristics.colors] already reads it. That is the
 * CR-correct treatment as well as the cheap one, because CR 702.114a makes devoid a
 * *characteristic-defining* ability functioning everywhere, including in zones the layer system does
 * not reach.
 *
 * 1. **"When you cast this spell, create two 0/1 Eldrazi Spawn tokens."** This is an ability of the
 *    *spell*, functioning from the stack (CR 603.2), and it resolves **before** the creature does — so
 *    the tokens arrive even if the Chrysalis is countered, which is the whole reason a sacrifice deck
 *    plays it. [dev.mtgplay.core.definition.TriggerZoneScope] has Battlefield, Graveyard and Exile and
 *    no Stack, and [TriggerCondition.SpellCast] is the *other-object* watcher a permanent has
 *    (Guttersnipe's "whenever you cast an instant or sorcery"). Encoding it as
 *    [TriggerCondition.EnteredBattlefieldSelf] would produce the same board on an uncontested cast and
 *    a different one whenever it matters — the plausible-looking wrong card PLAN.md §7 warns about.
 * 2. **"Whenever you sacrifice another Eldrazi, put a +1/+1 counter on this creature."** There is no
 *    sacrifice trigger condition at all: no [TriggerCondition] member watches CR 701.17, and none of
 *    the eleven members carries a **subtype** axis, which "another Eldrazi" needs (CR 205.3). Both are
 *    real framework additions — an event trigger plus a filtered subject — and neither is this
 *    packet's.
 *
 * ## Absent — Call Damage Control `{1}{G}`
 *
 * > **Choose up to two.** Return those cards from your graveyard to your hand. • Target artifact card.
 * > • Target creature card. • Target enchantment card. • Target land card.
 *
 * `FW-MODAL` landed with "choose one" only, and says so out loud: `SpellModes.kt` fails loudly on "a
 * mode arity other than one", and [SpellDefinition.modes]' own KDoc records that a count on the
 * declaration, a multi-select mode decision, and per-mode targets are what an arity other than one
 * needs. "Up to two" needs all three, plus a CR 601.2c same-object rule applied **across** modes (the
 * two chosen modes may not name the same graveyard card), which is a rule no existing target check
 * states. Separately, [dev.mtgplay.core.definition.GraveyardCardRestriction] has no artifact,
 * enchantment or land member — that part is cheap, and is not what blocks the card.
 */

/**
 * Extract a Confession — `{1}{B}` Sorcery. "As an additional cost to cast this spell, you may collect
 * evidence 6. (Exile cards with total mana value 6 or greater from your graveyard.) Each opponent
 * sacrifices a creature of their choice. If evidence was collected, instead each opponent sacrifices a
 * creature with the greatest power among creatures they control."
 *
 * **The pool's first spell whose *effect* is bought by an optional additional cost**, rather than a
 * trigger gated on one. Vitu-Ghazi Inspector's evidence buys a trigger that either fires or does not;
 * this one always resolves and the evidence changes *what the opponent may choose*. That is the reason
 * the two halves are one [dev.mtgplay.core.definition.EachOpponentSacrifices] clause with two narrowings
 * rather than two clauses or an `if` inside an effect: the printed word is "instead", and the difference
 * between the two lines is entirely in the option list handed to the opponent.
 *
 * **Sacrifice is not destruction and not targeting** (CR 701.17a), and both matter against this
 * gauntlet. It goes through indestructible and through regeneration, and — the reason a black deck plays
 * it over Terminate — it goes through **hexproof**: GW Bogles' Slippery Bogle cannot be targeted at all,
 * and a Bogles player with one creature must feed it to this. Nothing here targets, so nothing here can
 * be answered by protection or by a hexproof grant.
 *
 * **"Of their choice" is the opponent's, and the greatest-power narrowing is still a choice.** With a
 * suited-up 4/4 and a naked 1/1, unpaid Extract a Confession takes the 1/1 and evidence-paid Extract a
 * Confession takes the 4/4 — that is what the six mana value buys. But with *two* creatures tied at the
 * top the opponent still picks between them, and the engine enumerates both: choosing for them would
 * delete a real line (ADR-005), since which of two 3/3s dies is rarely a matter of indifference.
 *
 * **Power is effective power** (CR 613), read as the clause resolves. An Ethereal Armor on a 1/1 Bogle
 * makes it the greatest-power creature on the board, and this spell then demands exactly that creature —
 * which is precisely the interaction that makes the evidence mode worth paying for.
 *
 * Note what the card does **not** do: it does not draw, does not scale, and asks nothing of the caster's
 * own board. Casting it with an empty graveyard is a plain edict for `{1}{B}` and is often correct; the
 * engine settles the announcement silently to "no" in that case rather than offering a question with one
 * answer.
 */
val extractAConfession: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Extract a Confession",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.2c: the whole of the effect is the clause; there is nothing to run before it.
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 701.60a: "you may collect evidence 6."
        override val optionalAdditionalCost = OptionalAdditionalCost.CollectEvidence(EVIDENCE_AMOUNT)

        // CR 701.17a: "Each opponent sacrifices a creature of their choice. If evidence was collected,
        // instead ... a creature with the greatest power among creatures they control."
        override val eachOpponentSacrifices =
            EachOpponentSacrifices(
                cardType = CardType.CREATURE,
                narrowing = SacrificeNarrowing.ANY,
                narrowingWhenOptionalCostPaid = SacrificeNarrowing.GREATEST_POWER,
            )
    }

/** Vitu-Ghazi Inspector's printed identity, for its trigger's narration (CR 113.7c). */
private val VITU_GHAZI_INSPECTOR: CardRef = CardRef("Vitu-Ghazi Inspector")

/** Vitu-Ghazi Inspector's printed power (CR 208). */
private const val INSPECTOR_POWER: Int = 1

/** Vitu-Ghazi Inspector's printed toughness (CR 208). */
private const val INSPECTOR_TOUGHNESS: Int = 3

/** The total mana value both evidence cards collect (CR 701.60a). */
private const val EVIDENCE_AMOUNT: Int = 6

/** The life Vitu-Ghazi Inspector's trigger gains (CR 119.3). */
private const val INSPECTOR_LIFE_GAIN: Int = 2

/**
 * Vitu-Ghazi Inspector — `{1}{G}` Creature — Elf Detective, a 1/3 with reach. "As an additional cost to
 * cast this spell, you may collect evidence 6. (Exile cards with total mana value 6 or greater from your
 * graveyard.) When this creature enters, if evidence was collected, put a +1/+1 counter on target
 * creature and you gain 2 life."
 *
 * **The pool's first collect evidence** (CR 701.60a), and the card that shows why the keyword is an
 * [OptionalAdditionalCost] and not a [dev.mtgplay.core.definition.CastingPermission]: it *adds* to the
 * `{1}{G}`, it is refusable, and refusing it still casts the creature. What the engine had to grow for
 * it is a decision whose answer is validated by a **summed weight** rather than by a size — see
 * `DecisionRequest.SummedSelection`.
 *
 * **The announcement is gated on the graveyard's total, not on its emptiness**, and the difference is
 * the whole of ADR-005 here. A graveyard of four Forests is a long list that pays nothing; offering a
 * "yes" against it would open a selection stage with no legal answer. So the yes/no appears exactly when
 * the graveyard's mana values sum to 6 or more, and otherwise the announcement settles silently to "no"
 * and the Inspector is cast as a plain 1/3 with no question asked.
 *
 * **Exiling more than 6 is legal and is sometimes right**, which is why the engine offers the graveyard
 * flat rather than computing a payment for the caster. Which cards leave matters — a Deep Analysis or a
 * Gurmag Angler kept back is worth more than one exiled — and a green deck with a graveyard it wants
 * emptied may well exile the lot. Nothing here assumes a bigger graveyard is better for its owner.
 *
 * **The intervening "if" is not an `if` inside the effect** (CR 603.4), for [troublemakerOuphe]'s exact
 * reason: an Inspector cast without evidence *does not trigger at all*, so nothing goes on the stack and
 * no priority round opens for an opponent to respond in. "Evidence was collected" crosses CR 400.7 as
 * [InterveningIf.SourcePaidOptionalAdditionalCost] — the same recorded flag bargain uses, which is why
 * a card declares at most one such cost.
 *
 * Note what the trigger does **not** say: there is no "if able", so an evidence-collecting Inspector
 * entering onto a board with no legal creature target has its ability removed from the stack (CR 608.2b)
 * and gains no life either — the two halves are one effect, not two. Collecting evidence into that board
 * is still a legal play and stays enumerable; the 1/3 reach body is often the point.
 */
val vituGhaziInspector: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = VITU_GHAZI_INSPECTOR.name,
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Elf"), Subtype("Detective")),
                powerToughness =
                    PrintedPowerToughness(power = INSPECTOR_POWER, toughness = INSPECTOR_TOUGHNESS),
                // CR 702.17: reach, blocking-side only.
                keywords = persistentSetOf(Keyword.REACH),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: the engine puts the permanent onto the battlefield; the definition has nothing to do.
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 701.60a: "you may collect evidence 6."
        override val optionalAdditionalCost = OptionalAdditionalCost.CollectEvidence(EVIDENCE_AMOUNT)
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 603.4: the two-check clause, not a test inside the effect.
                    interveningIf = InterveningIf.SourcePaidOptionalAdditionalCost,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            val countered =
                                putCounters(
                                    state,
                                    counterTargetOf(context.targets),
                                    Counter.PLUS_ONE_PLUS_ONE,
                                )
                            gainLife(countered, context.controller, INSPECTOR_LIFE_GAIN)
                        },
                ),
            )
    }

/**
 * The single creature Vitu-Ghazi Inspector's trigger names (CR 115.1b). Fails loudly on anything else:
 * the CR 608.2b re-check has already run, so a resolving ability whose spec is a one-target
 * [TargetSpec.TargetPermanent] always holds exactly one legal permanent target (ADR-005).
 */
private fun counterTargetOf(targets: List<Target>): ObjectId {
    val target = targets.singleOrNull()
    require(target is Target.Permanent) {
        "CR 115.1b: ${VITU_GHAZI_INSPECTOR.name}'s trigger targets exactly one creature, got $targets"
    }
    return target.id
}

/** Troublemaker Ouphe's printed identity, for its trigger's narration (CR 113.7c). */
private val TROUBLEMAKER_OUPHE: CardRef = CardRef("Troublemaker Ouphe")

/** Troublemaker Ouphe's printed power (CR 208). */
private const val OUPHE_POWER: Int = 2

/** Troublemaker Ouphe's printed toughness (CR 208). */
private const val OUPHE_TOUGHNESS: Int = 2

/**
 * Troublemaker Ouphe — `{1}{G}` Creature — Ouphe, a 2/2. "Bargain (You may sacrifice an artifact,
 * enchantment, or token as you cast this spell.) When this creature enters, if it was bargained, exile
 * target artifact or enchantment an opponent controls."
 *
 * **Bargain is an optional additional cost with a chosen object** (CR 702.166a), the cost cell the
 * engine was missing — see [OptionalAdditionalCost] for the two-by-two that makes it its own shape. The
 * engine announces it as a yes/no whenever the board can pay it, then enumerates the artifacts,
 * enchantments and tokens the caster controls; declining is always legal and is an enumerated index
 * rather than an absent request, so an agent that *could* bargain is always shown that it could.
 *
 * **"Or token" is not a card type**, and that is the axis no existing filter had. A token is a non-card
 * game object (CR 111.1), so a 1/1 Warrior token qualifies while a Warrior creature *card* does not.
 * The engine tests it the way it tests tokenhood everywhere — `definitions[card] is TokenDefinition` —
 * rather than by inventing a pseudo card type, and the three arms are a genuine union: an artifact
 * token is offered once, an ordinary creature not at all.
 *
 * **The intervening "if" is not an `if` inside the effect** (CR 603.4), for the reason Goblin
 * Bushwhacker's is not: an unbargained Ouphe's ability *does not trigger at all*, so nothing goes on
 * the stack, no trigger is ordered, and no priority round opens for an opponent to respond to. Writing
 * the test into the effect would give an identical final board and an action space containing responses
 * the rules do not permit (ADR-005). "It was bargained" reaches the permanent across CR 400.7 the same
 * way kicked-ness does — as a flag the cast record carries onto the entering object — which is what
 * [InterveningIf.SourcePaidOptionalAdditionalCost] reads.
 *
 * **Exile, not destroy** (CR 701.3a): indestructible does not stop it, so a bargained Ouphe answers a
 * Bridge that a "destroy target artifact" cannot — which is why the gauntlet's Affinity and Tron decks
 * fear it. The target restriction is a union over two card types *and* a control test
 * ([PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS]), and hexproof narrows it
 * through the enumeration's own gate rather than through the restriction.
 *
 * Note what the trigger does **not** say: there is no "if able" and no untargeted fallback, so a
 * bargained Ouphe entering against a board with no opposing artifact or enchantment simply has no legal
 * target and its ability is removed from the stack (CR 608.2b). Bargaining into that board is a legal,
 * occasionally correct play — the 2/2 body is still worth the card — and the engine keeps it enumerable.
 */
val troublemakerOuphe: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TROUBLEMAKER_OUPHE.name,
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Ouphe")),
                powerToughness = PrintedPowerToughness(power = OUPHE_POWER, toughness = OUPHE_TOUGHNESS),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: the engine puts the permanent onto the battlefield; the definition has nothing to do.
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 702.166a: "You may sacrifice an artifact, enchantment, or token as you cast this spell."
        override val optionalAdditionalCost = OptionalAdditionalCost.Bargain
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 603.4: the two-check clause, not a test inside the effect.
                    interveningIf = InterveningIf.SourcePaidOptionalAdditionalCost,
                    targetSpec =
                        TargetSpec.TargetPermanent(
                            PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            exilePermanent(state, exileTargetOf(context.targets))
                        },
                ),
            )
    }

/**
 * The single permanent Troublemaker Ouphe's trigger names (CR 115.1b). Fails loudly on anything else:
 * the CR 608.2b re-check has already run, so a resolving ability whose spec is a one-target
 * [TargetSpec.TargetPermanent] always holds exactly one legal permanent target (ADR-005).
 */
private fun exileTargetOf(targets: List<Target>): ObjectId {
    val target = targets.singleOrNull()
    require(target is Target.Permanent) {
        "CR 115.1b: ${TROUBLEMAKER_OUPHE.name}'s trigger targets exactly one permanent, got $targets"
    }
    return target.id
}
