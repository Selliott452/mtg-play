package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.exilePermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **optional additional cost** cards (`FW-BARGAIN`): "you may [do this] as you cast this
 * spell", where the doing consumes an object the caster picks.
 *
 * One card is encoded — [troublemakerOuphe] — and it opens the cost cell
 * [dev.mtgplay.core.definition.OptionalAdditionalCost] describes: optional *and* object-choosing, the
 * one corner of the mandatory/optional by mana/non-mana square the engine did not have. Kicker is
 * optional with nothing to pick; Grab the Prize's discard picks but is mandatory; a
 * [dev.mtgplay.core.definition.CastingPermission] replaces the printed cost instead of adding to it.
 *
 * Three further cards this packet was offered are **absent**, each with a diagnosis below rather than
 * an approximation. All three oracle texts were checked against the repo's own Scryfall snapshot
 * (`mtg-pauper/src/main/resources/scryfall-mvp.json`), which is the authority, and all three agreed
 * with the packet brief.
 *
 * ## Absent — Extract a Confession `{1}{B}`, and Vitu-Ghazi Inspector `{1}{G}`
 *
 * > As an additional cost to cast this spell, you may **collect evidence 6**. (Exile cards with total
 * > mana value 6 or greater from your graveyard.)
 *
 * The *cost* is expressible in the framework this packet built — it is another
 * [OptionalAdditionalCost] member, announced by the same yes/no and gated on the same "could this be
 * paid at all" question (here: does the graveyard total 6 or more mana value?). What it needs beyond
 * that is a **decision shape the engine does not have**: a `MultiSelect` whose answer is validated by a
 * *summed weight* rather than by its size.
 *
 * `DecisionRequest` currently offers five answer shapes — [dev.mtgplay.rules.decision] `SizedSelection`
 * (exactly N), `RangedSelection` (between N and M), `PermutationSelection`, `ChoiceCountSelection`, and
 * `SingleOptionSelection`. Collect evidence fits none: the answer is "any subset whose total mana value
 * is at least 6", whose *size* is unbounded in both directions (six 1-drops or one 6-drop). Encoding it
 * as a `RangedSelection` over the graveyard would advertise size-legal answers that fail the sum, which
 * is the enumerate-then-reject defect ADR-005 forbids; encoding it as a `SizedSelection` at some fixed
 * count would delete every other legal payment. It needs a sixth sub-interface, its validation arm, and
 * the ~25 sites that switch on the family (drivers, CLI menus, the fuzz probe's generator, the DTO
 * tree, `RandomRemoteAgent`).
 *
 * **The bounding rule, recorded because the question is the interesting one.** Subsets of a graveyard
 * are exponential, so the instinct is to enumerate *minimal sufficient* subsets — those that reach 6
 * and drop below it if any card is removed. That rule is defensible but still exponential: a graveyard
 * of twenty one-drops has C(20,6) = 38,760 of them, and even deduplicating by printed identity leaves
 * hundreds. **The rule to use instead is not to enumerate subsets at all.** Offer the graveyard as a
 * flat option list — O(n), exactly as escape's "exile N other cards" does — and carry the constraint
 * *in the request* as a per-option mana value plus a threshold, validating the answer's sum. That is
 * complete (every legal payment is expressible), sound (no illegal one is), and linear. It is the same
 * move `ChooseCardsToExile` already makes with a scalar `count`; the only new thing is that the
 * constraint is a sum rather than a size, which is precisely why the sixth sub-interface is needed.
 *
 * Note a minimality rule would *also* be wrong on the merits, not merely expensive: exiling more than
 * the minimum is a real line, because which cards leave matters (a flashback or escape card kept back
 * is worth more than one exiled) and because the engine cannot know that a larger graveyard is always
 * better for its owner.
 *
 * Vitu-Ghazi Inspector needs nothing else — its "if evidence was collected" is
 * [InterveningIf.SourcePaidOptionalAdditionalCost], already built here, and its trigger puts a counter
 * and gains life. **Extract a Confession needs one thing more**: "each opponent sacrifices a creature
 * of their choice" is a `FW-NONCTRLDEC` clause with no member yet —
 * [dev.mtgplay.core.definition.EachOpponentDiscards] is the only one, and it chooses from a hand — and
 * the evidence mode *constrains* that choice to "a creature with the greatest power among creatures
 * they control", which is still a choice when tied and so cannot collapse to an engine pick.
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
