package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EachOpponentDiscards
import dev.mtgplay.core.definition.HandRevealChoice
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.definition.RevealedCardRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.exileLinkedToSource
import dev.mtgplay.rules.effect.flickerPermanent
import dev.mtgplay.rules.effect.flickerPermanents
import dev.mtgplay.rules.effect.returnExiledToBattlefield
import dev.mtgplay.rules.effect.returnExiledToOwnersHand
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's exile-and-return cards (docs/design/exile-and-return.md): the spells and permanents
 * that move an object to exile and bring it back, and the two that make an **opponent** reveal or
 * discard.
 *
 * Four frameworks land under these five cards, and each card is here because it is the minimal
 * demonstration of one of them:
 * - `FW-BLINK` — [ephemerate], the CR 400.7 exile-and-return with CR 603.6a re-triggering, plus
 *   rebound (CR 702.88) as a delayed cast from exile.
 * - `FW-TRIGLTB` — the general CR 603.6c leaves-the-battlefield trigger, which [journeyToNowhere] and
 *   [mesmericFiend] both need and which the narrower CR 603.6b graveyard condition cannot express.
 * - `FW-LINKEDEXILE` — the CR 607.2 linked-ability relationship, so that "return **the** exiled card"
 *   returns the card *this* permanent exiled and no other.
 * - `FW-HIDDENCHOICE` / `FW-NONCTRLDEC` — [duress] and [mesmericFiend] versus [refurbishedFamiliar],
 *   which look alike and are opposites: the first two are the *controller* choosing from information a
 *   reveal has made public, the third is an *opponent* choosing from a hand that stays hidden.
 *
 * **Ghostly Flicker arrived with `P-ABILSOURCE`**, and the note that used to stand here was wrong in
 * one respect worth recording. It said the card was blocked *only* on cardinality and that "the blink
 * half of it is entirely expressible with [flickerPermanent]". The cardinality half was indeed all
 * `FW-MULTITGT` owed it — [TargetSpec.TargetPermanent] now takes a [TargetCount] and
 * [dev.mtgplay.rules.decision.DecisionRequest.ChooseMultipleTargets] surfaces the choice — but the
 * blink half was *not* complete: folding [flickerPermanent] over two targets exiles and returns them
 * one at a time, and Ghostly Flicker's "then" makes both moves simultaneous. [flickerPermanents] is
 * that missing primitive; see [ghostlyFlicker].
 */

/** The resolution of a permanent spell: entering the battlefield is the whole of it (CR 608.3). */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Ephemerate — `{W}` Instant. "Exile target creature you control, then return it to the battlefield
 * under its owner's control. Rebound *(If you cast this spell from your hand, exile it as it resolves.
 * At the beginning of your next upkeep, you may cast this card from exile without paying its mana
 * cost.)*"
 *
 * The demonstration card for `FW-BLINK`, and it exercises the whole of it in one line: a CR 701.3a
 * exile and a CR 400.7 return inside a single resolution, so the creature comes back as a **new object**
 * — no counters (CR 122.2), no marked damage, untapped, summoning sick, stripped of any Aura, and with
 * its own enters-the-battlefield abilities **re-fired** (CR 603.6a). All of that is [flickerPermanent]'s,
 * and none of it is special-cased here.
 *
 * **"Target creature you control"** is [PermanentRestriction.CREATURE_YOU_CONTROL], the first
 * decider-relative permanent restriction (CR 109.5): the same Ephemerate offers each seat a different
 * option list, so the enumeration is asked per chooser rather than cached.
 *
 * **Rebound** ([SpellDefinition.rebound], CR 702.88a) is the reason this card is a framework and not a
 * one-liner. It replaces the CR 608.2m graveyard move *as the spell resolves* and *only* for a spell
 * cast from a hand — so the rebounded copy, which is cast from exile, finishes in the graveyard and the
 * loop terminates by the rule rather than by a guard. A countered or fizzled Ephemerate does not rebound
 * at all.
 */
val ephemerate: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ephemerate",
                manaCost = ManaCost.parse("{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)

        // CR 400.7: exile then return, in one resolution — the returning permanent is a new object.
        override val resolution =
            ResolutionEffect { state, context ->
                val target = context.targets.single()
                check(target is Target.Permanent) { "CR 115.1b: Ephemerate targets a creature, got $target" }
                flickerPermanent(state, target.id)
            }

        // CR 702.88a: exile as it resolves if cast from hand, then a free cast at the next upkeep.
        override val rebound = true
    }

/**
 * Journey to Nowhere — `{1}{W}` Enchantment. "When this enchantment enters, exile target creature. When
 * this enchantment leaves the battlefield, return the exiled card to the battlefield under its owner's
 * control."
 *
 * The demonstration card for `FW-LINKEDEXILE`, and the reason CR 607 needs modelling at all. Its two
 * abilities are a **linked pair** (CR 607.2): the second returns *the card the first exiled*, not the
 * last card anyone exiled and not every card in exile. Two Journeys on the battlefield each return their
 * own creature, which falls out of recording the exile on the source permanent
 * ([dev.mtgplay.core.state.GameObject.linkedExiled]) rather than in any global place.
 *
 * **The second ability is [TriggerCondition.LeftBattlefieldSelf], not the graveyard condition**, and
 * that distinction is the whole of `FW-TRIGLTB`. CR 603.6c fires on *every* departure; the narrower
 * CR 603.6b fires only on a departure that ends in a graveyard. Encoding this card with the narrower one
 * would mean that exiling the Journey in response left its creature exiled forever — a plausible-looking
 * wrong card, which is exactly what CONVENTIONS.md forbids.
 *
 * **The exiled creature comes back under its *owner's* control**, so a Journey cast on an opponent's
 * creature gives that creature back to the opponent when the Journey dies. Control is ownership in the
 * current pool, but [returnExiledToBattlefield] is written against the owner so the line stays right
 * when that changes.
 *
 * With **no** creature on the battlefield the enters-trigger is still put on the stack with no target
 * (CR 603.3d) and does nothing on resolution (CR 608.2b); nothing is recorded, so the leaves-trigger
 * later finds an empty link and also does nothing (CR 607.3).
 */
val journeyToNowhere: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Journey to Nowhere",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        // CR 302.1: an enchantment spell is cast at sorcery speed and targets nothing itself.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                // CR 603.6a + CR 607.2: exile the target, and record it on this permanent as the link.
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            val target = context.targets.singleOrNull()
                            val source = context.source
                            if (target !is Target.Permanent || source == null) {
                                state
                            } else {
                                exileLinkedToSource(state, target.id, source)
                            }
                        },
                ),
                // CR 603.6c + CR 607.2: on *any* departure, return exactly what this permanent exiled.
                TriggeredAbility(
                    condition = TriggerCondition.LeftBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            context.linkedExiled.fold(state, ::returnExiledToBattlefield)
                        },
                ),
            )
    }

/**
 * Mesmeric Fiend — `{1}{B}` Creature — Nightmare Horror 1/1. "When this creature enters, target opponent
 * reveals their hand and you choose a nonland card from it. Exile that card. When this creature leaves
 * the battlefield, return the exiled card to its owner's hand."
 *
 * The card where `FW-LINKEDEXILE` and `FW-HIDDENCHOICE` meet, and the one that most clearly shows why
 * the second framework is *not* `FW-NONCTRLDEC`. The printed text is "**you** choose a nonland card from
 * it": the opponent reveals — which is not a decision, because a player told to reveal their hand
 * reveals all of it — and then the Fiend's **controller** picks. The upstream brief filed this card as a
 * non-controller decision; the oracle text says otherwise, and the oracle text wins.
 *
 * The two abilities are linked (CR 607.2) exactly as Journey to Nowhere's are, with the one difference
 * that the return destination is a **hand** rather than the battlefield — so the card becomes hidden
 * again on arrival (CR 402.1), which the per-seat filter does by construction because it filters by zone
 * rather than by card.
 *
 * **The exile happens inside the clause, not inside an effect**, because the card to exile is chosen
 * mid-resolution; that is why the CR 607.2 record is written by the engine's clause application rather
 * than by a [ResolutionEffect] here. An opponent with a hand of nothing but lands reveals it, offers no
 * legal choice, and nothing is exiled — so the leaves-trigger later returns nothing (CR 607.3).
 */
val mesmericFiend: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Mesmeric Fiend",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Nightmare"), Subtype("Horror")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                // CR 603.6a: the reveal-and-choose is a clause, because the choice is mid-resolution.
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.TargetOpponent,
                    effect = ResolutionEffect { state, _ -> state },
                    handRevealChoice =
                        HandRevealChoice(
                            restriction = RevealedCardRestriction.NONLAND,
                            outcome = RevealedCardOutcome.EXILE_LINKED,
                        ),
                ),
                // CR 603.6c + CR 607.2: on any departure, return exactly the card this Fiend exiled.
                TriggeredAbility(
                    condition = TriggerCondition.LeftBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            context.linkedExiled.fold(state, ::returnExiledToOwnersHand)
                        },
                ),
            )
    }

/**
 * Duress — `{B}` Sorcery. "Target opponent reveals their hand. You choose a noncreature, nonland card
 * from it. That player discards that card."
 *
 * The minimal `FW-HIDDENCHOICE` card: the same clause Mesmeric Fiend carries, on a spell instead of a
 * triggered ability (the `FW-CLAUSEHOOK` carrier makes that a one-word difference), with a narrower
 * restriction and a discard instead of an exile.
 *
 * **The ADR-007 content of this card runs the opposite way to the obvious reading.** Duress does not
 * hide information — it *publishes* it. CR 701.16a's reveal makes the whole hand known to both seats for
 * as long as the reveal is open, so the seat view carries it in full and the enumerated options are
 * public by rule. The triage files this card under `FW-HIDDENCHOICE` and calls it "an ADR-007
 * per-seat-filter question, not merely a discard", which is right; the upstream brief files it under
 * non-controller decisions, which the printed "**You** choose" contradicts.
 *
 * The discard routes through the CR 614/616 replacement framework like every other discard, so an
 * opponent's madness card chosen by Duress is exiled instead and its reflexive cast fires **for that
 * opponent** — correct, and free from not special-casing the move.
 *
 * A hand with no noncreature, nonland card is still revealed and nothing is discarded.
 */
val duress: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Duress",
                manaCost = ManaCost.parse("{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.TargetOpponent
        override val resolution = ResolutionEffect { state, _ -> state }
        override val handRevealChoice =
            HandRevealChoice(
                restriction = RevealedCardRestriction.NONCREATURE_NONLAND,
                outcome = RevealedCardOutcome.DISCARD,
            )
    }

/**
 * Refurbished Familiar — `{3}{B}` Artifact Creature — Zombie Rat 2/1. "Affinity for artifacts *(This
 * spell costs {1} less to cast for each artifact you control.)* Flying. When this creature enters, each
 * opponent discards a card. For each opponent who can't, you draw a card."
 *
 * The demonstration card for `FW-NONCTRLDEC`, and the only one of these five whose decision genuinely
 * belongs to somebody other than the resolving object's controller. "Each opponent discards a card"
 * means each of them chooses **which card of their own**, out of a hand the controller may not see
 * (CR 402.1) — so the deciding seat is handed its own hand as enumerated options (ADR-005) and the
 * controller is handed a count (ADR-007). docs/design/exile-and-return.md §6.1 states the ruling.
 *
 * **Affinity was already built** (`FW-COST`, docs/design/cost-modification.md) and needed nothing from
 * this packet — the brief asked whether it was now sufficient, and it is. This card reuses
 * `CostReductionCards.kt`'s existing `affinityForArtifacts` declaration unchanged rather than restating
 * CR 702.41a, so there is exactly one place the printed line is encoded. Flying likewise predates this
 * packet. The discard clause is the entire remaining gap, which is why this card is the framework's
 * whole test.
 *
 * "For each opponent who can't, you draw a card" needs no second decision: an opponent with an empty
 * hand cannot discard, so no request is surfaced for that seat at all and the controller draws instead.
 */
val refurbishedFamiliar: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Refurbished Familiar",
                manaCost = ManaCost.parse("{3}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Zombie"), Subtype("Rat")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 1),
                // CR 702.9: flying is a plain printed keyword the combat rules already read.
                keywords = persistentSetOf(Keyword.FLYING),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield

        // CR 702.41a: affinity for artifacts, already provided by `FW-COST` and reused unchanged.
        override val costReduction = affinityForArtifacts
        override val triggeredAbilities =
            persistentListOf(
                // CR 603.6a + CR 701.7a: each opponent discards, deciding for themselves.
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                    eachOpponentDiscards =
                        EachOpponentDiscards(count = 1, drawPerOpponentWhoCannot = 1),
                ),
            )
    }

/** Ghostly Flicker's printed target count (CR 601.2c): exactly two, never fewer. */
private const val GHOSTLY_FLICKER_TARGETS = 2

/**
 * Ghostly Flicker — `{2}{U}` Instant. "Exile two target artifacts, creatures, and/or lands you
 * control, then return those cards to the battlefield under your control."
 *
 * The UWX Familiar deck's engine card, and the reason two separate frameworks had to land before it
 * could be encoded honestly.
 *
 * **Two targets, one instance of the word "target", one union noun.** "Artifacts, creatures, and/or
 * lands you control" is [PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL] — a single
 * disjunctive restriction rather than three specs — with [TargetCount.Exactly] two. That shape is what
 * makes CR 601.2c's same-object rule apply: the two chosen permanents must be *different*, which
 * `requireWellFormedTargetChoice` enforces against one duplicate-free enumeration. Three parallel
 * specs would need a distinctness rule spanning them, and no such rule exists.
 *
 * **"Exactly two", not "up to two"** — so the spell is **uncastable with fewer than two** qualifying
 * permanents (CR 601.2c via `targetsAvailable`, which tests the spec's minimum). That is not a
 * simplification: a lone Archaeomancer cannot be blinked by this card, and the deck's own mana base is
 * what usually supplies the second target.
 *
 * **The blink is simultaneous** ([flickerPermanents]): both permanents are exiled, and only then are
 * both returned. Folding [flickerPermanent] twice would interleave the moves, so each permanent's
 * enters-the-battlefield triggers (CR 603.6a) would be detected while the other was in the wrong zone.
 * The famous loop — Ghostly Flicker on Archaeomancer plus a land, the Archaeomancer returning Ghostly
 * Flicker itself to hand — depends on that ETB re-firing, which is CR 400.7's doing rather than this
 * card's.
 *
 * It is an **instant**, which is most of why the card is good: blinking in response to targeted removal
 * makes the removal fizzle at CR 608.2b, and untapping two lands mid-combat is the rest of it.
 */
val ghostlyFlicker: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ghostly Flicker",
                manaCost = ManaCost.parse("{2}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED

        // CR 115.1b with CR 109.5: one instance of "target" over a union noun, taken exactly twice.
        override val targetSpec =
            TargetSpec.TargetPermanent(
                restriction = PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
                count = TargetCount.Exactly(GHOSTLY_FLICKER_TARGETS),
            )
        override val resolution =
            ResolutionEffect { state, context ->
                flickerPermanents(state, targetedPermanents(context.targets, "Ghostly Flicker"))
            }
    }

/**
 * The battlefield permanents a multi-target spell chose (CR 115.1b), in the order they were chosen.
 * Fails loudly on any other target shape: CR 601.2c and the CR 608.2b re-check have both already run
 * by resolution time, so a non-permanent target here is an engine defect (ADR-005).
 */
private fun targetedPermanents(
    targets: List<Target>,
    cardName: String,
): List<ObjectId> =
    targets.map { target ->
        (target as? Target.Permanent)?.id
            ?: error("CR 115.1b: $cardName targets permanents on the battlefield, got $target")
    }
