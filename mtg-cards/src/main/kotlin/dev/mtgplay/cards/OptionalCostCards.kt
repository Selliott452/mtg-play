package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastCondition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.counterSpell
import dev.mtgplay.rules.effect.isCreaturePermanent
import dev.mtgplay.rules.effect.spellManaValueOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **optional-cost** cards (`FW-OPTCOST`, `FW-ALTCOST`): the two kickers and the one
 * alternative cost that is not a mana cost at all.
 *
 * Three cards, three different halves of "a cost you may choose to pay":
 *
 * | Card | The choice | What reads the answer |
 * |---|---|---|
 * | [goblinBushwhacker] | Kicker `{R}` (CR 702.33a) | its own enters trigger's CR 603.4 intervening-if |
 * | [prohibit] | Kicker `{2}` | its own resolution, through the cast record |
 * | [landGrant] | Reveal your hand rather than pay (CR 118.9) | nothing — the choice *is* the cost |
 *
 * **Kicker is an optional *additional* cost, which was a genuinely new shape.** The engine already had
 * mandatory additional costs ([dev.mtgplay.core.definition.AdditionalCost] — Grab the Prize discards
 * whether you like it or not) and alternative costs that *replace* the printed one
 * ([CastingPermission] — Fireblast). Kicker is neither: it is announced at CR 601.2b, adds to the total
 * cost at CR 601.2f, and declining is always legal. Both cards here read the answer back afterwards,
 * which is CR 702.33f's linked information — and the two read it through *different* routes, because
 * one is a permanent and one is not.
 *
 * Oracle text below is Scryfall's, fetched for this packet (`POST /cards/collection`); where it
 * disagreed with the triage or the packet brief, the oracle text won. Two of the five cards this packet
 * was offered are absent for that reason and are named in the packet report: **Kaervek's Torch**, whose
 * "spells that target it cost {2} more to cast" is a cost *increase* keyed on another spell's chosen
 * targets, and **Nyxborn Hydra**, which needs bestow.
 */

/** Goblin Bushwhacker's printed power and toughness (CR 208). */
private const val BUSHWHACKER_POWER: Int = 1

/** Goblin Bushwhacker's printed toughness (CR 208). */
private const val BUSHWHACKER_TOUGHNESS: Int = 1

/** The power the kicked Bushwhacker's trigger grants each creature its controller controls (CR 613.3). */
const val GOBLIN_BUSHWHACKER_POWER_BONUS: Int = 1

/** The mana value Prohibit counters up to when it was **not** kicked (CR 202.3). */
const val PROHIBIT_UNKICKED_MAX_MANA_VALUE: Int = 2

/** The mana value Prohibit counters up to when it **was** kicked (CR 202.3). */
const val PROHIBIT_KICKED_MAX_MANA_VALUE: Int = 4

/**
 * Goblin Bushwhacker — `{R}` Creature — Goblin Warrior, a 1/1. "Kicker {R} (You may pay an additional
 * {R} as you cast this spell.) When this creature enters, if it was kicked, creatures you control get
 * +1/+0 and gain haste until end of turn."
 *
 * The card the whole optional-cost framework exists for, because it is the one that needs the answer
 * **after the spell has stopped existing**. The spell and the creature are different objects (CR 400.7),
 * so "it was kicked" survives the move only because CR 702.33f says it does; the engine carries the fact
 * onto the entering permanent as [dev.mtgplay.core.state.GameObject.kickedWhenCast], and this ability's
 * [InterveningIf.SourceWasKicked] reads it there.
 *
 * **The intervening-if is not an `if` inside the effect, and the difference is visible.** CR 603.4 is a
 * two-check rule: an unkicked Bushwhacker's ability *does not trigger at all*, so nothing goes on the
 * stack, no trigger is ordered, and no priority round opens for an opponent to respond to. Writing the
 * test into the effect instead would implement only the resolution half — identical final board, and an
 * action space with responses in it that the rules do not permit (ADR-005).
 *
 * **The affected set is folded, not selected, and that is CR 611.2c rather than a shortcut.** "Creatures
 * you control" is fixed **once, when the effect begins** — a creature that arrives later is unaffected,
 * and one that leaves keeps nothing. Folding [applyUntilEndOfTurn] over the battlefield as it stands at
 * resolution produces exactly that: one continuous effect per creature, each with its own affected
 * object, all created at the same moment. The triage filed this card as needing an
 * [dev.mtgplay.core.definition.AffectedSet] "wider than `Enchanted`"; that type describes the affected
 * set of a **static** ability declared on a permanent (CR 604.3), and this is a resolution-generated
 * effect (CR 611.2), which has always named its object directly. No framework was missing here — see the
 * packet report.
 *
 * Haste (CR 702.10) is a layer-6 grant the engine has carried since `FW-COUNTERS`, so the pump and the
 * keyword travel in one [ContinuousModification]; the creatures it makes hasty include the Bushwhacker
 * itself, which is the card's whole purpose in a one-turn aggro deck.
 */
val goblinBushwhacker: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Goblin Bushwhacker",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Goblin"), Subtype("Warrior")),
                powerToughness =
                    PrintedPowerToughness(power = BUSHWHACKER_POWER, toughness = BUSHWHACKER_TOUGHNESS),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.3: the engine puts the permanent onto the battlefield; the definition has nothing to do.
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 702.33a: "You may pay an additional {R} as you cast this spell."
        override val kicker = ManaCost.parse("{R}")
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 603.4: checked when the trigger would fire *and* again on resolution.
                    interveningIf = InterveningIf.SourceWasKicked,
                    effect =
                        ResolutionEffect { state, context ->
                            pumpAndHasteYourCreatures(state, context.controller, context.source)
                        },
                ),
            )
    }

/**
 * "Creatures you control get +1/+0 and gain haste until end of turn" (CR 611.2, CR 613.3 layers 6 and
 * 7c) — Goblin Bushwhacker's kicked trigger, applied to the affected set as it stands right now
 * (CR 611.2c).
 *
 * Control is ownership in this pool (docs/design/layer-system.md §4), so the filter is `owner == seat`;
 * creature-ness is the engine's own published read ([isCreaturePermanent]), so a future type-changing
 * effect reaches this card and the combat engine at the same moment.
 */
private fun pumpAndHasteYourCreatures(
    state: GameState,
    seat: dev.mtgplay.core.identity.PlayerId,
    source: dev.mtgplay.core.identity.ObjectId?,
): GameState {
    // CR 611.2c: the set is fixed here, from this state — a creature that arrives later is unaffected.
    val affected =
        state.sharedZones.battlefield
            .filter { it.owner == seat && isCreaturePermanent(state, it) }
            .map { it.id }
    return affected.fold(state) { current, id ->
        applyUntilEndOfTurn(
            current,
            affected = id,
            modification =
                ContinuousModification(
                    grantedKeywords = persistentSetOf(Keyword.HASTE),
                    powerMod = GOBLIN_BUSHWHACKER_POWER_BONUS,
                ),
            sourceCard =
                dev.mtgplay.core.identity
                    .CardRef("Goblin Bushwhacker"),
            source = source,
        )
    }
}

/**
 * Prohibit — `{1}{U}` Instant. "Kicker {2} (You may pay an additional {2} as you cast this spell.)
 * Counter target spell if its mana value is 2 or less. If this spell was kicked, counter that spell if
 * its mana value is 4 or less instead."
 *
 * The other half of kicker's linked information: unlike Goblin Bushwhacker this spell reads the answer
 * **during its own resolution**, off its own cast record, so no marker has to survive a zone change —
 * the spell is still the object that was cast. [dev.mtgplay.core.definition.ResolutionContext.kicked]
 * carries it.
 *
 * **The mana value test is a *condition*, not a targeting restriction, and that distinction is the one
 * docs/design/countering-spells.md §1.2 most warns about.** Prohibit targets [SpellRestriction.Any] — it
 * may be cast at a spell of any size, and it simply does nothing to one too large. Encoding "mana value
 * 2 or less" as a target restriction would be an enumeration gap in the wrong direction twice over: it
 * would hide the legal (if pointless) cast at a big spell, and — because the threshold moves with the
 * kicker — it would need a restriction that depends on a CR 601.2b announcement the engine has not made
 * yet when targets are enumerated.
 *
 * **The mana value read is the target's, on the stack, including its own announced X** (CR 202.3b): a
 * Kaervek's Torch cast for X = 5 is a mana value 6 spell on the stack, and Prohibit would not counter it
 * even kicked. That is why [spellManaValueOf] reads the stack entry rather than the printed cost — the
 * triage's note that "mana value is read from the printed cost" is right only for a spell with no X, and
 * the packet report records the correction.
 *
 * A target that has left the stack never reaches the resolution: CR 608.2b fizzles the counter first.
 */
val prohibit: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Prohibit",
                manaCost = ManaCost.parse("{1}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any)

        // CR 702.33a: "You may pay an additional {2} as you cast this spell."
        override val kicker = ManaCost.parse("{2}")
        override val resolution =
            ResolutionEffect { state, context ->
                val target =
                    context.targets.singleOrNull()
                        ?: error("CR 601.2c: Prohibit counters exactly one target spell, got ${context.targets}")
                // CR 702.33f: the linked information, read off this spell's own cast record.
                val threshold =
                    if (context.kicked) PROHIBIT_KICKED_MAX_MANA_VALUE else PROHIBIT_UNKICKED_MAX_MANA_VALUE
                // CR 202.3b: the target's mana value *as a spell on the stack*, X included.
                if (spellManaValueOf(state, target) <= threshold) {
                    counterSpell(state, target, context.source)
                } else {
                    // CR 608.2: an instruction whose condition is false simply does nothing. The spell
                    // still resolved and still goes to its owner's graveyard; it is not a fizzle.
                    state
                }
            }
    }

/**
 * Land Grant — `{1}{G}` Sorcery. "If you have no land cards in hand, you may reveal your hand rather
 * than pay this spell's mana cost. Search your library for a Forest card, reveal that card, put it into
 * your hand, then shuffle."
 *
 * A prior packet encoded the search half and dropped the card, because the first sentence needed two
 * things [CastingPermission.AlternativeCost] did not have — and it really was two, not one:
 *
 * - a **non-mana, non-sacrifice cost component**. Revealing your hand consumes nothing and moves
 *   nothing; it *publishes* (CR 701.16a). The permission carries [CastingPermission.revealsHand] and the
 *   engine pays it by emitting the reveal, with no decision and no pause — there is nothing to choose,
 *   and it can never fail, so an empty hand is a legal payment.
 * - a **condition on the game state**. Every permission before this one gated on where the card was and
 *   how it got there — a marker on the object itself. This one gates on the caster's hand and can flip
 *   between one priority window and the next without the card moving: draw a land and the free cast
 *   disappears. [CastCondition.NoLandCardsInHand] is the declaration; `mtg-rules` evaluates it and
 *   simply does not enumerate the permission when it is false (ADR-005), so the option a seat can see is
 *   one it can complete.
 *
 * **The two casts are distinct enumerated options, exactly as Fireblast's are.** With no land in hand
 * and two mana available a seat sees both — pay `{1}{G}`, or reveal and pay nothing — because they leave
 * genuinely different positions (one spends mana, the other spends information).
 *
 * **ADR-007: this is the first cost whose payment *widens* what an opponent may see.** The condition is
 * read against the caster's own hidden hand and discloses nothing (cast options are enumerated only for
 * the seat holding priority). The reveal is the opposite and is the printed card doing it: CR 701.16a
 * makes the cards public, and the engine narrates them to both seats.
 */
val landGrant: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Land Grant",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 118.9: the alternative cost replaces the printed one entirely — no mana, a hand reveal,
        // and only while the caster holds no land card.
        override val castingPermissions =
            listOf(
                CastingPermission.AlternativeCost(
                    cost = ManaCost.parse("{0}"),
                    condition = CastCondition.NoLandCardsInHand,
                    revealsHand = true,
                ),
            )

        // CR 701.18: "Search your library for a Forest card, reveal that card, put it into your hand,
        // then shuffle" — the reveal is folded into the destination, which is the rule rather than a
        // coincidence (see LibrarySearchDestination).
        override val librarySearch =
            LibrarySearch(
                find = LibrarySearchFilter.FOREST_CARD,
                destination = LibrarySearchDestination.REVEALED_TO_HAND,
            )
    }
