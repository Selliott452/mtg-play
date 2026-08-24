package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's lifegain commons that need no framework the engine lacks (docs/decklists.md): the
 * enters-the-battlefield gainers [healerOfTheGlade] (Spy Combo) and [spinewoodsPaladin] (Elves), the
 * lifelink body with a death trigger [outlawMedic] (Gates), the graveyard-counting [gnawToTheBone]
 * (Elves), the hand-counting [unionOfTheThirdPath] (Jeskai Ephemerate), and the tap-for-Elves
 * [wellwisher] (Elves). Spirit Link — the Aura half of the same family — lives in Auras.kt with the
 * other Auras.
 *
 * Every mechanism is a published DSL primitive (ADR-003): enters-the-battlefield and dies triggers
 * (CR 603.6a–b), the lifelink keyword (CR 702.15), flashback and plot (CR 702.34, CR 702.140), a
 * `{T}` activated ability (CR 602), and the [gainLife]/[drawCards] effects. The two variable amounts —
 * Gnaw to the Bone's per-creature-card and Wellwisher's per-Elf — are pure counts over the live
 * [GameState] the [ResolutionEffect] is handed (ADR-004), the [galvanicBlast] pattern; no card action
 * is gap-avoided.
 */

/** The life Healer of the Glade's enters-the-battlefield trigger gains (CR 119.3). */
const val HEALER_OF_THE_GLADE_LIFEGAIN: Int = 3

/** The cards Outlaw Medic's dies trigger draws (CR 120.1). */
const val OUTLAW_MEDIC_DRAW: Int = 1

/** The life Gnaw to the Bone gains **per creature card** in its controller's graveyard (CR 119.3). */
const val GNAW_TO_THE_BONE_LIFE_PER_CREATURE_CARD: Int = 2

/** The cards Union of the Third Path draws before counting the hand (CR 120.1). */
const val UNION_OF_THE_THIRD_PATH_DRAW: Int = 1

/** The life Spinewoods Paladin's enters-the-battlefield trigger gains (CR 119.3). */
const val SPINEWOODS_PALADIN_LIFEGAIN: Int = 3

/** The life Wellwisher's activated ability gains **per Elf** on the battlefield (CR 119.3). */
const val WELLWISHER_LIFE_PER_ELF: Int = 1

/** The creature type Wellwisher counts (CR 205.3m). */
private val ELF: Subtype = Subtype("Elf")

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. Shared by this file's creatures, whose printed
 * work is a triggered or activated ability, never a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** Whether the object [obj] is a creature **card** (CR 205.2) — a token is not a card (CR 111). */
private fun isCreatureCard(
    state: GameState,
    obj: GameObject,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.cardTypes
        ?.contains(CardType.CREATURE) == true

/** Whether the object [obj] has the printed creature type [subtype] (CR 205.3); an inert object has none. */
private fun hasSubtype(
    state: GameState,
    obj: GameObject,
    subtype: Subtype,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.subtypes
        ?.contains(subtype) == true

/**
 * Healer of the Glade — `{G}` Creature — Elemental, a 1/2. "When this creature enters, you gain 3
 * life." The body enters the battlefield with no resolution instructions (CR 608.3); the whole printed
 * text is one enters-the-battlefield trigger (CR 603.6a) gaining its controller
 * [HEALER_OF_THE_GLADE_LIFEGAIN] life (CR 119.3).
 */
val healerOfTheGlade: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Healer of the Glade",
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Elemental")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 2),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, HEALER_OF_THE_GLADE_LIFEGAIN)
                        },
                ),
            )
    }

/**
 * Outlaw Medic — `{1}{W}` Creature — Human Rogue, a 1/3 with lifelink. "Lifelink. When this creature
 * dies, draw a card." Two halves, neither a resolution instruction (CR 608.3):
 * - lifelink is a printed keyword (CR 702.15), a *result of the damage* with no stack and no trigger —
 *   the same keyword the Aura named [lifelink] grants, here printed on the body itself;
 * - "when this creature dies" is "when it is put into a graveyard from the battlefield" (CR 700.4),
 *   the [TriggerCondition.PutIntoGraveyardFromBattlefieldSelf] leaves-the-battlefield trigger
 *   (CR 603.6b) [rancor] already uses. It fires however the creature dies — a lethal-damage
 *   state-based action (CR 704.5g), a sacrifice, or a sweeper — and draws [OUTLAW_MEDIC_DRAW]
 *   (CR 120.1) for its controller. Per CR 603.10 the trigger is checked against the state just before
 *   the creature left, so it fires even though the source is no longer on the battlefield.
 */
val outlawMedic: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Outlaw Medic",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 3),
                keywords = persistentSetOf(Keyword.LIFELINK),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, OUTLAW_MEDIC_DRAW)
                        },
                ),
            )
    }

/**
 * Gnaw to the Bone — `{2}{G}` Instant. "You gain 2 life for each creature card in your graveyard.
 * Flashback `{2}{G}`." Two mechanisms:
 * - the resolution gains [GNAW_TO_THE_BONE_LIFE_PER_CREATURE_CARD] life per creature **card** in the
 *   controller's graveyard (CR 119.3), counted as the spell resolves (CR 608.2) from the live state.
 *   "Card" excludes a token that somehow reached the graveyard (CR 111 — a token is not a card, and
 *   the CR 704.5d state-based action removes it anyway), and the spell never counts *itself*: it is on
 *   the stack while it resolves, not in the graveyard — including when flashed back;
 * - the flashback half is [CastingPermission.Flashback]`({2}{G})` (CR 702.34), cast from the graveyard
 *   and exiled as it leaves the stack (CR 702.34e). The Elves list plays it as two lifegain spells in
 *   one slot precisely because the graveyard it counts is the one it is cast from.
 */
val gnawToTheBone: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gnaw to the Bone",
                manaCost = ManaCost.parse("{2}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val creatureCards =
                    state
                        .players
                        .getValue(context.controller)
                        .graveyard
                        .count { isCreatureCard(state, it) }
                gainLife(state, context.controller, GNAW_TO_THE_BONE_LIFE_PER_CREATURE_CARD * creatureCards)
            }
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{2}{G}")))
    }

/**
 * Union of the Third Path — `{2}{W}` Instant. "Draw a card, then you gain life equal to the number of
 * cards in your hand." One resolution, two instructions in printed order (CR 608.2c), and the "then"
 * is load-bearing: the hand is counted **after** the draw, so the drawn card counts itself. Draws
 * [UNION_OF_THE_THIRD_PATH_DRAW] (CR 120.1), then gains that many life (CR 119.3).
 *
 * The spell itself is on the stack, not in hand, while it resolves, so it never counts itself.
 */
val unionOfTheThirdPath: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Union of the Third Path",
                manaCost = ManaCost.parse("{2}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val drawn = drawCards(state, context.controller, UNION_OF_THE_THIRD_PATH_DRAW)
                gainLife(
                    drawn,
                    context.controller,
                    drawn.players
                        .getValue(context.controller)
                        .hand.size,
                )
            }
    }

/**
 * Spinewoods Paladin — `{4}{G}` Creature — Human Knight, a 5/4 with trample. "Trample. When this
 * creature enters, you gain 3 life. Plot `{3}{G}`." Three printed halves, all published primitives:
 * - trample is a printed keyword (CR 702.19) the combat engine already assigns through;
 * - the lifegain is an enters-the-battlefield trigger (CR 603.6a) for
 *   [SPINEWOODS_PALADIN_LIFEGAIN] (CR 119.3), the same shape as [healerOfTheGlade]'s;
 * - plot is [CastingPermission.Plot]`({3}{G})` (CR 702.140), the permission [highwayRobbery] already
 *   uses: the card is plotted (pay `{3}{G}`, exile it face up from hand, only as a sorcery) and cast
 *   for free from exile on a **later** turn at sorcery speed. Plotting a creature turns a five-drop
 *   into two three-mana halves, which is why the Elves list wants it.
 */
val spinewoodsPaladin: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Spinewoods Paladin",
                manaCost = ManaCost.parse("{4}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Knight")),
                powerToughness = PrintedPowerToughness(power = 5, toughness = 4),
                keywords = persistentSetOf(Keyword.TRAMPLE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, SPINEWOODS_PALADIN_LIFEGAIN)
                        },
                ),
            )
        override val castingPermissions = listOf(CastingPermission.Plot(ManaCost.parse("{3}{G}")))
    }

/**
 * Wellwisher — `{1}{G}` Creature — Elf, a 1/1. "`{T}`: You gain 1 life for each Elf on the
 * battlefield." The body enters with no resolution instructions (CR 608.3); its whole printed text is
 * one activated ability (CR 602) whose only cost is the `{T}` symbol ([AbilityCost.TapSelf]).
 *
 * Two CR points the encoding leans on:
 * - `{T}` on a creature is barred by summoning sickness (CR 302.6), which the activation pipeline
 *   already enforces — Wellwisher gains nothing the turn it arrives;
 * - "each Elf **on the battlefield**" is every Elf under **either** controller, including Wellwisher
 *   itself (CR 109.5 — an object with the Elf creature type, CR 205.3m). In an Elves mirror the
 *   opponent's board pays too, which is the card's whole reputation.
 *
 * The count is taken as the ability resolves (CR 608.2) from the live state, so an Elf that dies in
 * response is not counted; the amount is [WELLWISHER_LIFE_PER_ELF] per Elf (CR 119.3).
 */
val wellwisher: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Wellwisher",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(ELF),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    effect =
                        ResolutionEffect { state, context ->
                            val elves = state.sharedZones.battlefield.count { hasSubtype(state, it, ELF) }
                            gainLife(state, context.controller, WELLWISHER_LIFE_PER_ELF * elves)
                        },
                ),
            )
    }
