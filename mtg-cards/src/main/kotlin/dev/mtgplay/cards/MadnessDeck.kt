package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.DrawThenDiscard
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.OptionalCostThenDraw
import dev.mtgplay.core.definition.OptionalDiscardDraw
import dev.mtgplay.core.definition.ReplacementEffect
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.dealDamageToEachOpponent
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.returnFromGraveyardToBattlefieldTapped
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The Mono-Red Madness deck (docs/decklists.md), encoded over the engine as pure card data. Every mechanism
 * these cards use — spell-cast triggers (both the instant-or-sorcery filter of [guttersnipe] and the
 * noncreature filter of [kessigFlamebreather]), per-turn draw counting from the graveyard, ETB triggers with
 * optional discard-then-draw, madness (discard→exile replacement + reflexive cast), flashback and its
 * non-mana sacrifice cost, alternative sacrifice costs, additional discard costs with linked information,
 * plot, composite activated-ability costs (now including a token's, see [bloodToken]), an optional
 * cost-then-draw at spell resolution ([highwayRobbery]), and a mandatory draw-then-discard at spell
 * resolution ([faithlessLooting]) — is a published DSL primitive (ADR-003), so each definition below is a
 * faithful oracle translation. P6.2c closed the last four architect gaps (Blood's activated ability, Highway
 * Robbery's cost-then-draw, Faithless Looting's resolution discard, and Ash Barrens' search); no card action
 * is gap-avoided anywhere.
 */

/** The damage Guttersnipe's spell-cast trigger deals to each opponent (CR 120.3a). */
const val GUTTERSNIPE_DAMAGE: Int = 2

/** The damage Kessig Flamebreather's noncreature-cast trigger deals to each opponent (CR 120.3a). */
const val KESSIG_FLAMEBREATHER_DAMAGE: Int = 1

/** The damage Voldaren Epicure's enters-the-battlefield trigger deals to each opponent (CR 120.3a). */
const val VOLDAREN_EPICURE_DAMAGE: Int = 1

/** The damage a resolving Fiery Temper deals to its target (CR 120.3a). */
const val FIERY_TEMPER_DAMAGE: Int = 3

/** The damage a resolving Fireblast deals to its target (CR 120.3a). */
const val FIREBLAST_DAMAGE: Int = 4

/** The damage a resolving Lava Dart deals to its target (CR 120.3a). */
const val LAVA_DART_DAMAGE: Int = 1

/** The cards Grab the Prize draws on resolution (CR 120.1). */
const val GRAB_THE_PRIZE_DRAW: Int = 2

/** The damage Grab the Prize deals to each opponent when the discarded card wasn't a land (CR 120.3a). */
const val GRAB_THE_PRIZE_DAMAGE: Int = 2

/** The cards Melded Moxite's enters-the-battlefield "if you do, draw" clause draws (CR 601.3b). */
const val MELDED_MOXITE_DRAW: Int = 2

/** The draw of the turn (the third) that fires Sneaky Snacker's graveyard return trigger (CR 603.2). */
const val SNEAKY_SNACKER_DRAW_ORDINAL: Int = 3

/**
 * What Fireblast's alternative cost and Lava Dart's flashback cost may be paid with (CR 601.2h,
 * CR 205.3): a permanent with the printed land subtype Mountain. A [SacrificeFilter] since `W8-D` gave
 * the permission-side [SacrificeRequirement] the same filter every other sacrifice cost uses.
 */
private val MOUNTAIN_SACRIFICE: SacrificeFilter = SacrificeFilter(subtype = Subtype("Mountain"))

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield; the [ResolutionEffect] is a no-op. Shared by the
 * Madness creatures ([guttersnipe], [sneakySnacker], [voldarenEpicure]) and the artifact [meldedMoxite]
 * — their whole "resolution" is entering the battlefield; their enters-the-battlefield work is a
 * triggered ability (CR 603.6a), not a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** The cards the Blood token's activated ability draws on resolution (CR 120.1). */
const val BLOOD_TOKEN_DRAW: Int = 1

/**
 * The Blood token (CR 111.4) Voldaren Epicure creates: a colorless artifact token of subtype Blood with
 * "{1}, {T}, Discard a card, Sacrifice this token: Draw a card" (CR 602). The rummaging loot the deck uses
 * to filter draws and — its whole point in Madness — to pitch a madness card (Fiery Temper) for value.
 *
 * The activated ability is a composite cost ([AbilityCost.Mana]`({1})` + [AbilityCost.TapSelf] +
 * [AbilityCost.DiscardACard] + [AbilityCost.SacrificeSelf], in printed order) whose effect draws
 * [BLOOD_TOKEN_DRAW] (CR 120.1). The engine reads it through the same `definitions[card].activatedAbilities`
 * path a real card's ability uses (CR 113.6): the discard cost routes through the CR 614/616 framework, so
 * a discarded madness card is exiled instead and its reflexive cast fires (P6.2c completed the
 * [TokenDefinition.activatedAbilities] field this needs). Being an artifact, not a creature, the token may
 * tap and sacrifice for the ability the turn it is created (no summoning-sickness bar on `{T}`, CR 302.6).
 */
val bloodToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Blood",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Blood")),
                powerToughness = null,
            ),
        activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.TapSelf,
                            AbilityCost.DiscardACard,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, BLOOD_TOKEN_DRAW)
                        },
                ),
            ),
    )

/**
 * The Robot token (CR 111.4) Melded Moxite's activated ability creates: a 2/2 colorless Robot artifact
 * creature. Created **tapped** (the ability says so) via [createTapped]; entering tapped otherwise has no
 * dedicated create-token option, so the effect taps the freshly created object through the public state
 * API rather than a new engine primitive.
 */
val robotToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Robot",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Robot")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            ),
    )

/**
 * Creates [token] under [controller]'s control and returns it **tapped** (CR 110.5b) — Melded Moxite's
 * "Create a tapped … Robot token". [createToken] enters an untapped object (CR 110.5a); with no
 * create-token-tapped primitive in the P6.2a effect set, this composes the tap onto the created object
 * through the published state API: it identifies the new object (the one battlefield id absent before the
 * create) and copies it tapped. Pure and deterministic (ADR-002).
 */
private fun createTapped(
    state: GameState,
    controller: PlayerId,
    token: TokenDefinition,
): GameState {
    val before =
        state.sharedZones.battlefield
            .map { it.id }
            .toSet()
    val created = createToken(state, controller, token)
    val createdId =
        created.sharedZones.battlefield
            .firstOrNull { it.id !in before }
            ?.id
            ?: error("CR 707.2: createToken must add exactly one new battlefield object")
    return created.copy(
        sharedZones =
            created.sharedZones.copy(
                battlefield =
                    created.sharedZones.battlefield
                        .map { if (it.id == createdId) it.copy(tapped = true) else it }
                        .toPersistentList(),
            ),
    )
}

/**
 * Guttersnipe — `{2}{R}` Creature — Goblin Shaman, a 2/2. "Whenever you cast an instant or sorcery spell,
 * this creature deals 2 damage to each opponent." The body enters the battlefield with no resolution
 * instructions (CR 608.3); the printed text is one triggered ability (CR 603.2) watching the cast-trigger
 * seam filtered to the caster's own instants and sorceries
 * ([TriggerCondition.SpellCast]`(spellTypes = {INSTANT, SORCERY}, controlledByYou = true)`, CR 603.2e),
 * whose resolution deals [GUTTERSNIPE_DAMAGE] to each opponent (CR 120.3a).
 */
val guttersnipe: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Guttersnipe",
                manaCost = ManaCost.parse("{2}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Goblin"), Subtype("Shaman")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition =
                        TriggerCondition.SpellCast(
                            spellTypes = persistentSetOf(CardType.INSTANT, CardType.SORCERY),
                            controlledByYou = true,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamageToEachOpponent(
                                state,
                                context.damageSource(),
                                context.controller,
                                GUTTERSNIPE_DAMAGE,
                            )
                        },
                ),
            )
    }

/**
 * Kessig Flamebreather — `{1}{R}` Creature — Human Shaman, a 1/3. "Whenever you cast a noncreature
 * spell, this creature deals 1 damage to each opponent." The deck's second cast-trigger body (P6.3,
 * replacing Melded Moxite in the list): the body enters the battlefield with no resolution instructions
 * (CR 608.3), and the printed text is one triggered ability (CR 603.2) on the cast-trigger seam.
 *
 * Its filter is the **noncreature** shape — `SpellCast(excludedSpellTypes = {CREATURE},
 * controlledByYou = true)` (CR 603.2e) — not Guttersnipe's instant-or-sorcery whitelist: it also fires
 * on this deck's artifact and enchantment spells, and an artifact *creature* spell is a creature spell,
 * so it is excluded by type rather than by not being whitelisted. Its resolution deals
 * [KESSIG_FLAMEBREATHER_DAMAGE] to each opponent (CR 120.3a).
 */
val kessigFlamebreather: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Kessig Flamebreather",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Shaman")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 3),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition =
                        TriggerCondition.SpellCast(
                            excludedSpellTypes = persistentSetOf(CardType.CREATURE),
                            controlledByYou = true,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            dealDamageToEachOpponent(
                                state,
                                context.damageSource(),
                                context.controller,
                                KESSIG_FLAMEBREATHER_DAMAGE,
                            )
                        },
                ),
            )
    }

/**
 * Sneaky Snacker — `{U}{B}` Creature — Faerie Rogue, a 1/1 with flying. "When you draw your third card in
 * a turn, return this card from your graveyard to the battlefield tapped." Castable in principle at its
 * `{U}{B}` cost (CR 601), but its practical life is the graveyard: the deck discards it and it recurs
 * itself. Both halves are encoded honestly — the printed body (a 1/1 flyer) for the cast path, and the
 * graveyard-scoped per-turn draw trigger ([TriggerCondition.DrewNthCardThisTurn]`(3)`, CR 603.2, functioning
 * from [TriggerZoneScope.Graveyard], CR 113.6) whose resolution returns the captured graveyard object to
 * the battlefield tapped (CR 400.7).
 */
val sneakySnacker: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Sneaky Snacker",
                manaCost = ManaCost.parse("{U}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Faerie"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.DrewNthCardThisTurn(SNEAKY_SNACKER_DRAW_ORDINAL),
                    effect =
                        ResolutionEffect { state, context ->
                            returnFromGraveyardToBattlefieldTapped(
                                state,
                                context.subject
                                    ?: error("CR 603.10: Sneaky Snacker's draw trigger carries the graveyard object"),
                            )
                        },
                    zoneScope = TriggerZoneScope.Graveyard,
                ),
            )
    }

/**
 * Voldaren Epicure — `{R}` Creature — Vampire, a 1/1. "When this creature enters, it deals 1 damage to
 * each opponent. Create a Blood token." The body enters with no resolution instructions (CR 608.3); its
 * one enters-the-battlefield trigger (CR 603.6a) deals [VOLDAREN_EPICURE_DAMAGE] to each opponent
 * (CR 120.3a) and then creates the [bloodToken] under its controller (CR 707.2). The two effects are one
 * trigger, sequenced as printed (damage, then token). The created [bloodToken] carries its own
 * "{1}, {T}, Discard a card, Sacrifice this token: Draw a card" activated ability (CR 602).
 */
val voldarenEpicure: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Voldaren Epicure",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Vampire")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
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
                            val burned =
                                dealDamageToEachOpponent(
                                    state,
                                    context.damageSource(),
                                    context.controller,
                                    VOLDAREN_EPICURE_DAMAGE,
                                )
                            createToken(burned, context.controller, bloodToken)
                        },
                ),
            )
    }

/**
 * Fiery Temper — `{1}{R}{R}` Instant. "Fiery Temper deals 3 damage to any target. Madness `{R}`." An
 * any-target burn spell (CR 115.4) dealing [FIERY_TEMPER_DAMAGE] on resolution (CR 120.3a), plus madness
 * (CR 702.35): the [ReplacementEffect.DiscardToExileInstead] (CR 702.35a) exiles it instead of discarding
 * it, and the [CastingPermission.Madness]`({R})` (CR 702.35b) lets its owner cast it from exile for `{R}`
 * as the reflexive trigger resolves. The Madness deck's core enabler — discarded to a Blood token, a Grab
 * the Prize cost, or cleanup, it comes back as a `{R}` bolt.
 */
val fieryTemper: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fiery Temper",
                manaCost = ManaCost.parse("{1}{R}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamage(state, context.damageSource(), context.targets.single(), FIERY_TEMPER_DAMAGE)
            }
        override val castingPermissions = listOf(CastingPermission.Madness(ManaCost.parse("{R}")))
        override val replacementEffects =
            persistentListOf<ReplacementEffect>(ReplacementEffect.DiscardToExileInstead)
    }

/**
 * Fireblast — `{4}{R}{R}` Instant. "You may sacrifice two Mountains rather than pay this spell's mana
 * cost. Fireblast deals 4 damage to any target." An any-target burn spell dealing [FIREBLAST_DAMAGE] on
 * resolution (CR 120.3a). The free-with-lands line is a [CastingPermission.AlternativeCost] (CR 118.9):
 * its `{0}` mana cost *replaces* the printed `{4}{R}{R}`, plus the non-mana
 * [dev.mtgplay.core.definition.SacrificeRequirement] of two Mountains (CR 601.2h). Both the normal cast
 * and the alternative cast are enumerated when affordable (ADR-005).
 */
val fireblast: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Fireblast",
                manaCost = ManaCost.parse("{4}{R}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamage(state, context.damageSource(), context.targets.single(), FIREBLAST_DAMAGE)
            }
        override val castingPermissions =
            listOf(
                CastingPermission.AlternativeCost(
                    cost = ManaCost.parse("{0}"),
                    sacrifice = SacrificeRequirement(count = 2, filter = MOUNTAIN_SACRIFICE),
                ),
            )
    }

/**
 * Lava Dart — `{R}` Instant. "Lava Dart deals 1 damage to any target. Flashback—Sacrifice a Mountain." An
 * any-target burn spell dealing [LAVA_DART_DAMAGE] on resolution (CR 120.3a), with a non-mana flashback
 * cost (CR 702.34c): [CastingPermission.Flashback] with a `{0}` mana cost and the
 * [dev.mtgplay.core.definition.SacrificeRequirement] of one Mountain. Flashed back from the graveyard the
 * spell is exiled instead of returning there as it leaves the stack (CR 702.34e), so each copy deals its
 * point twice across a game.
 */
val lavaDart: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Lava Dart",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamage(state, context.damageSource(), context.targets.single(), LAVA_DART_DAMAGE)
            }
        override val castingPermissions =
            listOf(
                CastingPermission.Flashback(
                    cost = ManaCost.parse("{0}"),
                    sacrifice = SacrificeRequirement(count = 1, filter = MOUNTAIN_SACRIFICE),
                ),
            )
    }

/**
 * Grab the Prize — `{1}{R}` Sorcery. "As an additional cost to cast this spell, discard a card. Draw two
 * cards. If the discarded card wasn't a land card, Grab the Prize deals 2 damage to each opponent." The
 * additional discard is an [AdditionalCost.DiscardCards]`(1)` (CR 601.2b): the engine surfaces the
 * selection, checks payability (the spell is uncastable with no other card to discard), and records the
 * discarded card's identity as linked information ([ResolutionContext.discardedForCost], CR 118.9). The
 * discard routes through the CR 614/616 framework, so a discarded madness card (Fiery Temper) is exiled
 * instead and its reflexive cast fires. Resolution draws [GRAB_THE_PRIZE_DRAW] (CR 120.1) and, reading the
 * linked information, deals [GRAB_THE_PRIZE_DAMAGE] to each opponent iff the discarded card was not a land
 * (CR 120.3a).
 */
val grabThePrize: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Grab the Prize",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val additionalCost = AdditionalCost.DiscardCards(1)
        override val resolution =
            ResolutionEffect { state, context ->
                val drawn = drawCards(state, context.controller, GRAB_THE_PRIZE_DRAW)
                val discarded = context.discardedForCost.singleOrNull()
                val discardedWasLand =
                    discarded != null &&
                        drawn.definitions[discarded]
                            ?.characteristics
                            ?.cardTypes
                            ?.contains(CardType.LAND) == true
                if (discardedWasLand) {
                    drawn
                } else {
                    dealDamageToEachOpponent(drawn, context.damageSource(), context.controller, GRAB_THE_PRIZE_DAMAGE)
                }
            }
    }

/**
 * Melded Moxite — `{1}{R}` Artifact. "When this artifact enters, you may discard a card. If you do, draw
 * two cards. `{3}`, Sacrifice this artifact: Create a tapped 2/2 colorless Robot artifact creature token."
 * An artifact permanent spell (cast at sorcery speed, CR 601.3a) whose resolution is entering the
 * battlefield (CR 608.3). Two abilities:
 * - an enters-the-battlefield trigger (CR 603.6a) carrying the optional discard-then-draw clause
 *   [OptionalDiscardDraw]`(2)` (CR 601.3b) — the madness pattern, a first-class Blood-token-style enabler:
 *   the engine offers the yes/no, the discard selection (routed through CR 614/616 so a madness card is
 *   exiled instead), and the [MELDED_MOXITE_DRAW]-card draw;
 * - an activated ability (CR 602) with the composite cost `{3}` + sacrifice-self ([AbilityCost.Mana] +
 *   [AbilityCost.SacrificeSelf]) whose effect creates the tapped [robotToken] (see [createTapped]).
 */
val meldedMoxite: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Melded Moxite",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = entersTheBattlefield,
                    optionalDiscardDraw = OptionalDiscardDraw(MELDED_MOXITE_DRAW),
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{3}")), AbilityCost.SacrificeSelf),
                    effect = ResolutionEffect { state, context -> createTapped(state, context.controller, robotToken) },
                ),
            )
    }

/** The cards Highway Robbery's "if you do, draw" clause draws when a cost is paid (CR 601.3b). */
const val HIGHWAY_ROBBERY_DRAW: Int = 2

/**
 * Highway Robbery — `{1}{R}` Sorcery. "You may discard a card or sacrifice a land. If you do, draw two
 * cards. Plot `{1}{R}`." Two mechanisms:
 * - the resolution clause is an *optional cost-then-draw at spell resolution* (CR 601.3b):
 *   [OptionalCostThenDraw]`([HIGHWAY_ROBBERY_DRAW], [discard | sacrifice-a-land])` — `mtg-rules` offers the
 *   controller a mode choice (decline, discard a card, or sacrifice a land), then that mode's object
 *   selection, then the draw. A discarded madness card (Fiery Temper) is exiled instead (CR 702.35a),
 *   routing through the CR 614/616 framework;
 * - the plot half is [CastingPermission.Plot]`({1}{R})` (CR 702.140) — the card is plotted (paid `{1}{R}`,
 *   exiled face-up) and cast for free from exile on a later turn at sorcery speed.
 */
val highwayRobbery: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Highway Robbery",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val optionalCostThenDraw =
            OptionalCostThenDraw(
                drawCount = HIGHWAY_ROBBERY_DRAW,
                modes = persistentListOf(OptionalCostMode.DiscardCard, OptionalCostMode.SacrificeLand),
            )
        override val castingPermissions = listOf(CastingPermission.Plot(ManaCost.parse("{1}{R}")))
    }

/** The cards Faithless Looting draws on resolution, then the number it discards (CR 601.2c). */
const val FAITHLESS_LOOTING_DRAW: Int = 2

/** The cards Faithless Looting discards after drawing (CR 601.2c). */
const val FAITHLESS_LOOTING_DISCARD: Int = 2

/**
 * Faithless Looting — `{R}` Sorcery. "Draw two cards, then discard two cards. Flashback `{2}{R}`." Two
 * mechanisms:
 * - the resolution is a mandatory [DrawThenDiscard]`([FAITHLESS_LOOTING_DRAW], [FAITHLESS_LOOTING_DISCARD])`
 *   (CR 601.2c): the engine draws two, then pauses for the mandatory discard of two hand cards — each routed
 *   through the CR 614/616 framework, so a discarded madness card (Fiery Temper) is exiled instead and its
 *   reflexive cast fires. This is the Madness deck's flagship loot-into-madness line;
 * - the flashback half is [CastingPermission.Flashback]`({2}{R})` (CR 702.34), cast from the graveyard and
 *   exiled as it leaves the stack (CR 702.34e).
 */
val faithlessLooting: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Faithless Looting",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val drawThenDiscard = DrawThenDiscard(FAITHLESS_LOOTING_DRAW, FAITHLESS_LOOTING_DISCARD)
        override val castingPermissions = listOf(CastingPermission.Flashback(ManaCost.parse("{2}{R}")))
    }
