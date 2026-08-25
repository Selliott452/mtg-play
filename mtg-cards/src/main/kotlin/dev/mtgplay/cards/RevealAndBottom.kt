package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's "look, keep what matches, bottom the rest" cards: Ancient Stirrings, Augur of Bolas, and
 * Lead the Stampede. All three declare the one mode this packet added,
 * [LibraryLookMode.RevealMatchingToHandRestToBottom] — `FW-LIBLOOK`'s named non-goal "a filter on the keep"
 * (docs/design/library-look.md §12), filled in for exactly the cards that note listed.
 *
 * **The oracle text disagrees with both the design note and the triage, and the oracle text wins.**
 * `library-look.md` §9 and `gauntlet-card-triage-2.md` both file **Lead the Stampede** as a *reveal* —
 * "Reveal the top five cards of your library. Put all creature cards revealed this way into your hand and
 * the rest on the bottom" — and the triage therefore asked for a rest-to-bottom destination on
 * [dev.mtgplay.core.definition.LibraryReveal] and a *mandatory* keep-all. Its current Scryfall oracle text
 * is a **look** with an **optional** keep: "Look at the top five cards of your library. You may reveal any
 * number of creature cards from among them and put the revealed cards into your hand. Put the rest on the
 * bottom of your library in any order." That is Ancient Stirrings' clause with a wider allowance, not a
 * reveal at all — so the card lands here beside its two siblings, no destination axis was added to
 * `LibraryReveal`, and no keep in this file is mandatory. Encoding the printed-but-superseded wording would
 * have made the top five cards public and forced a keep the card does not force: two real rules errors.
 *
 * What the three exercise between them: the filter (three of the five [RevealedCardFilter] members), both
 * allowances (at most one, and any number), both carriers (a sorcery's CR 601 resolution and a creature's
 * CR 603 enters trigger), and the partial publicity the mode introduces — the kept cards are revealed
 * (CR 701.16a) and the bottomed cards are not (CR 701.14a).
 */

/** How deep Ancient Stirrings looks (CR 701.14a) — the deepest look in the gauntlet. */
const val ANCIENT_STIRRINGS_LOOK: Int = 5

/** How deep Augur of Bolas' enters-the-battlefield trigger looks (CR 701.14a). */
const val AUGUR_OF_BOLAS_LOOK: Int = 3

/** How deep Lead the Stampede looks (CR 701.14a). */
const val LEAD_THE_STAMPEDE_LOOK: Int = 5

/** "You may reveal **a** … card from among them": at most one card may be kept. */
private const val KEEP_AT_MOST_ONE: Int = 1

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules engine
 * moves it from the stack onto the battlefield. Augur of Bolas uses it twice — as the creature spell's
 * resolution and as its trigger's effect, because everything the trigger does is its clause.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Ancient Stirrings — `{G}` Sorcery. "Look at the top five cards of your library. You may reveal a colorless
 * card from among them and put it into your hand. Then put the rest on the bottom of your library in any
 * order."
 *
 * The **filter on the keep**, and the reason it had to be a filter on the *look* rather than on the reveal.
 * Every one of the five cards is seen by its controller and by no one else (CR 701.14a); only the card that
 * is kept is shown to everyone (CR 701.16a). Encoding it as a
 * [dev.mtgplay.core.definition.LibraryReveal] would have published the other four — including the order they
 * were bottomed in, which is the hidden state the card is played for.
 *
 * "A colorless card" is [RevealedCardFilter.COLORLESS_CARD], read off the **printed mana cost** (CR 202.2),
 * which is why every land in the pool qualifies (a land has no mana cost at all) alongside the artifacts and
 * the Eldrazi this card is played to find. It is deliberately not encoded as "an artifact or land card": the
 * two agree on almost every card Monster Tron plays and disagree on a colorless *creature*, and the printed
 * rule is the one the card states.
 *
 * The keep is **optional** — "You may reveal" — so declining is an enumerated arrangement (index 0), unlike
 * [seaGateOracle]'s mandatory one. With all five looked-at cards colorless the space is
 * `1 * 5! + 5 * 4! = 240` arrangements, comfortably inside the engine's 720 budget.
 */
val ancientStirrings: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ancient Stirrings",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook =
            LibraryLook(
                mode =
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = ANCIENT_STIRRINGS_LOOK,
                        toHand = RevealedCardFilter.COLORLESS_CARD,
                        maxToHand = KEEP_AT_MOST_ONE,
                    ),
            )
    }

/**
 * Augur of Bolas — `{1}{U}` Creature — Merfolk Wizard, a 1/3. "When this creature enters, look at the top
 * three cards of your library. You may reveal an instant or sorcery card from among them and put it into
 * your hand. Put the rest on the bottom of your library in any order."
 *
 * [ancientStirrings]' clause three cards deep, on a **trigger** instead of a spell — the `FW-CLAUSEHOOK`
 * carrier doing its job for a mode it predates. The engine runs one orchestration for both; the paths
 * diverge only at the end, where the ability ceases to exist (CR 113.7a) and Ancient Stirrings' card goes to
 * a graveyard (CR 608.2m).
 *
 * The trigger's effect is the no-op [entersTheBattlefield]: everything the ability does is its clause, which
 * the engine runs after the effect. The creature is already on the battlefield when the trigger resolves
 * (CR 603.6a puts the ability on the stack *after* the permanent enters), so the look never sees a library
 * the creature's own resolution has yet to touch.
 */
val augurOfBolas: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Augur of Bolas",
                manaCost = ManaCost.parse("{1}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Merfolk"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 3),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = entersTheBattlefield,
                    libraryLook =
                        LibraryLook(
                            mode =
                                LibraryLookMode.RevealMatchingToHandRestToBottom(
                                    count = AUGUR_OF_BOLAS_LOOK,
                                    toHand = RevealedCardFilter.INSTANT_OR_SORCERY_CARD,
                                    maxToHand = KEEP_AT_MOST_ONE,
                                ),
                        ),
                ),
            )
    }

/**
 * Lead the Stampede — `{2}{G}` Sorcery. "Look at the top five cards of your library. You may reveal any
 * number of creature cards from among them and put the revealed cards into your hand. Put the rest on the
 * bottom of your library in any order."
 *
 * The **any-number** allowance, and the card that shows why it needed no new field. "Any number of creature
 * cards from among them" is bounded above by the pool, which holds at most five, so it is `maxToHand =
 * count` — every subset of the matching cards is enumerated, up to all of them. Under ADR-005 an
 * enumeration *is* the legality rule, so two phrasings admitting the same option list are the same decision;
 * a separate `anyNumber` flag would be a field no branch could read differently (see
 * [LibraryLookMode.RevealMatchingToHandRestToBottom]).
 *
 * With all five looked-at cards creatures the space is `sum over k of C(5, k) * (5 - k)! = 326`
 * arrangements — the widest single decision in the encoded pool, and still inside the 720 budget. Elves and
 * Spy Combo both play it as their card-advantage engine, which is why the empty keep is enumerated too: with
 * no creature in the top five the card genuinely does nothing but reorder the bottom.
 */
val leadTheStampede: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lead the Stampede",
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val libraryLook =
            LibraryLook(
                mode =
                    LibraryLookMode.RevealMatchingToHandRestToBottom(
                        count = LEAD_THE_STAMPEDE_LOOK,
                        toHand = RevealedCardFilter.CREATURE_CARD,
                        maxToHand = LEAD_THE_STAMPEDE_LOOK,
                    ),
            )
    }
