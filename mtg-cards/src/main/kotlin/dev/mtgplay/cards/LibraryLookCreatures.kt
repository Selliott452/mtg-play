package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's blue enters-the-battlefield look creatures: Faerie Seer and Sea Gate Oracle. They are the
 * demonstration cards of `FW-CLAUSEHOOK` (docs/design/resolution-clause-hook.md) — the packet that lifted
 * the four post-resolution clauses off `SpellDefinition` onto a carrier a [TriggeredAbility] implements too.
 *
 * The point is what is *not* here. Faerie Seer's scry 2 is byte-for-byte the [LibraryLook] clause Preordain
 * carries; Sea Gate Oracle's is Impulse's, two cards deep instead of four. Neither card needed a new mode, a
 * new decision, a new pending record, or a line of orchestration — only the hook they hang from had to stop
 * being spell-shaped. A creature's enters trigger is CR 603 where a sorcery's resolution is CR 601, and CR
 * 701.14a does not care which put the look on the stack.
 *
 * Their three siblings in the same triage row stay unencoded, each blocked on a framework this packet does
 * not deliver: **Lembas** (scry 1 then draw — the clause is fine) needs `FW-SHUFFLEIN` for its
 * "its owner shuffles it into their library" dies trigger; **Conduit Pylons** needs surveil (CR 701.44, a
 * graveyard destination this framework's modes do not have) *and* `FW-MANA`'s "add one mana of any color";
 * **Giant's Boulder** — whose enters trigger is scry 2, not the surveil it is sometimes filed under — needs
 * `FW-MANA` and a `{7}` destroy-target-permanent ability. Partial encodings would be the silent
 * approximation CONVENTIONS.md forbids, so all three are absences rather than lies.
 */

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. Used twice over by both creatures here — once as the
 * *spell's* resolution, and once as the enters trigger's [TriggeredAbility.effect], because everything each
 * ability does is its [LibraryLook] clause, which the engine runs after the effect.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** How deep Faerie Seer's enters-the-battlefield trigger scries (CR 701.17a). */
const val FAERIE_SEER_SCRY: Int = 2

/** How many cards Sea Gate Oracle's enters-the-battlefield trigger looks at (CR 701.14a). */
const val SEA_GATE_ORACLE_LOOK: Int = 2

/**
 * Faerie Seer — `{U}` Creature — Faerie Wizard, a 1/1 with flying. "Flying. When this creature enters,
 * scry 2."
 *
 * The minimal proof that the clause hook is no longer spell-shaped. The body is an ordinary flying one-drop
 * (CR 702.9, a printed [Keyword]); the whole card is its enters-the-battlefield trigger (CR 603.6a), and
 * that trigger carries [LibraryLookMode.Scry]`(2)` — the *same* [LibraryLook] value Preordain declares,
 * on a [TriggeredAbility] instead of a [SpellDefinition]. The engine runs it through one orchestration:
 * six enumerated arrangements (CR 701.17a's `(2 + 1)!` partitions-with-order), a private look that emits no
 * [dev.mtgplay.core.event.GameEvent.CardsRevealed], and then — the one place the paths diverge — the
 * ability ceases to exist (CR 113.7a) where Preordain's card goes to a graveyard (CR 608.2m).
 *
 * The trigger's [TriggeredAbility.effect] is [entersTheBattlefield], the no-op: everything this ability
 * does is the clause, which runs after the effect. Note the ordering the card depends on — the creature is
 * already on the battlefield when the trigger resolves (CR 603.6a puts it on the stack *after* the
 * permanent enters), so the scry never sees a library the creature's own resolution has yet to touch.
 */
val faerieSeer: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Faerie Seer",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Faerie"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = entersTheBattlefield,
                    libraryLook = LibraryLook(mode = LibraryLookMode.Scry(FAERIE_SEER_SCRY)),
                ),
            )
    }

/**
 * Sea Gate Oracle — `{2}{U}` Creature — Human Wizard, a 1/3. "When this creature enters, look at the top
 * two cards of your library. Put one of them into your hand and the other on the bottom of your library."
 *
 * The **mandatory keep** on an ability, which is the half of the hook a scry alone would not have proved.
 * [LibraryLookMode.OneToHandRestToBottom]`(2)` enumerates no arrangement whose hand group is empty, so the
 * "put one of them into your hand" the card prints is not enforced by a validation rule but by the absence
 * of any index that would decline it (ADR-005 — legality is defined by the enumeration). That asymmetry
 * against [dev.mtgplay.core.definition.LibraryReveal]'s always-optional keep is Impulse's, reached here
 * from CR 603 instead of CR 601.
 *
 * "The other on the bottom" is the mode's rest-to-bottom disposition; with two cards there is exactly one
 * card left, so the ordering the mode also enumerates is degenerate here — the card is Impulse at depth two,
 * which is precisely why it is the cheap second demonstration rather than a new mode. On a library of one
 * the pool is short and the CR's "do as much as possible" applies: one card, and it goes to the hand.
 */
val seaGateOracle: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sea Gate Oracle",
                manaCost = ManaCost.parse("{2}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Wizard")),
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
                        LibraryLook(mode = LibraryLookMode.OneToHandRestToBottom(SEA_GATE_ORACLE_LOOK)),
                ),
            )
    }
