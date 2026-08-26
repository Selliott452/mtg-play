package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.goad
import dev.mtgplay.rules.effect.loseLife
import dev.mtgplay.rules.effect.putCounters
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * **The Undercity** (CR 309) and the two gauntlet cards that walk you into it (`W10-A`).
 *
 * The initiative is the largest single mechanic the gauntlet prints. `mtg-rules` now implements all of
 * it — the designation (CR 701.51a), the upkeep venture (CR 701.51b), the combat-damage handover
 * (CR 701.51c), and the venture keyword action with its CR 309.4 branch decision — and this file is the
 * card half: the one dungeon a "take the initiative" effect enters, encoded room by room from the
 * printed card.
 *
 * ---
 *
 * **Avenging Hunter and Goliath Paladin are NOT registered, and the Undercity is why.** Both are
 * ordinary bodies with one printed line — *"When this creature enters, you take the initiative."* — and
 * that line now works. What does not work is two of the Undercity's nine rooms, and one of them is the
 * room every path ends at, so the dungeon cannot be walked to its end:
 *
 * 1. **Arena — "Goad target creature."** Goad (CR 701.38a) is *"until your next turn, that creature
 *    attacks each combat if able and attacks a player other than you if able"*. The second half is
 *    vacuous in a two-player game; the first half is an **attack requirement** (CR 508.1d), and the
 *    engine has no requirement framework at all. `eligibleAttackers` publishes a free subset — a
 *    creature may attack, never must — and `DecisionValidation` accepts any distinct subset of it. Goad
 *    needs the declaration to be *constrained*, which is a change to the enumerated action space
 *    (ADR-005) rather than an effect. It also needs a duration the engine does not have: "until your
 *    next turn" is neither [dev.mtgplay.core.state.EffectDuration.UntilEndOfTurn] nor `Indefinite`, and
 *    it ends at the start of a *later* turn, so the CR 514.2 cleanup cannot end it. Arena is reachable
 *    from both Forge and Lost Well, so it cannot be routed around either.
 * 2. **Throne of the Dead Three — "Reveal the top ten cards of your library. Put a creature card from
 *    among them onto the battlefield with three +1/+1 counters on it. It gains hexproof until your next
 *    turn. Then shuffle."** Four separate gaps, and this room is the dungeon's **last**, so no path
 *    avoids it:
 *    - [dev.mtgplay.core.definition.LibraryReveal] distributes to {hand, graveyard} and has no
 *      destination axis at all — "onto the battlefield" is a new one, and a *permanent entering from a
 *      reveal* is a path `announceBattlefieldEntry` has never been reached from.
 *    - "with three +1/+1 counters on it" is a CR 614.1c **enters-with-counters** replacement, which
 *      `CountersOnPermanents.kt` has recorded as absent since Nyxborn Hydra. Placing counters
 *      ([putCounters]) is a different mechanism and would be a different card — it happens after the
 *      permanent has entered, so an enters-the-battlefield trigger reading power would read the wrong
 *      number.
 *    - "hexproof until your next turn" needs the same missing duration goad does, plus a layer-6 grant
 *      of [Keyword.HEXPROOF] with it.
 *    - "Then shuffle" puts the **unchosen revealed cards back into the library**; every existing reveal
 *      puts them in a graveyard.
 *
 * Shipping the cards with those two rooms silently doing nothing would be the plausible-looking wrong
 * card PLAN.md §7 forbids: an agent would learn that Arena is free and that the Undercity's payoff room
 * is a blank, which is most of what the mechanic is played for. Shipping them with the rooms *throwing*
 * would be worse — an illegal line the engine enumerates and then crashes on is the failure ADR-005
 * calls out by name, and Throne is unavoidable, so every initiative game would reach it.
 *
 * So the dungeon is encoded **whole and honestly**: nine rooms, real names, real successors, seven real
 * abilities, and two [DungeonRoomAbility.Unimplemented] records carrying their printed text and the
 * diagnosis above. `mtg-rules` refuses to enter an unimplemented room, and `InitiativeSpec` pins that
 * [undercity] has exactly these two — so the packet that adds goad and the four Throne pieces deletes
 * assertions and registers two cards, rather than discovering the gap in a game.
 */

/** The Skeleton creature type (CR 205.3m) the Undercity's Catacombs token carries. */
private val SKELETON: Subtype = Subtype("Skeleton")

/** The Treasure artifact type (CR 205.3g) the Undercity's Stash token carries. */
private val TREASURE: Subtype = Subtype("Treasure")

/** The five colours a Treasure's mana ability may add (CR 105.1). */
private val ANY_COLOR =
    persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)

/** Forge's "**two** +1/+1 counters" (CR 122.1). */
private const val FORGE_COUNTERS: Int = 2

/** Trap!'s "loses **5** life" (CR 119.3). */
private const val TRAP_LIFE_LOSS: Int = 5

/** Lost Well's "**Scry 2**" (CR 701.17a). */
private const val LOST_WELL_SCRY: Int = 2

/** Archives' "Draw **a** card" (CR 121.1). */
private const val ARCHIVES_DRAW: Int = 1

/** The Catacombs Skeleton's printed power (CR 208.1). */
private const val SKELETON_POWER: Int = 4

/** The Catacombs Skeleton's printed toughness (CR 208.1). */
private const val SKELETON_TOUGHNESS: Int = 1

/**
 * The Treasure token the Undercity's Stash creates (CR 111.4): *"Treasure — `{T}`, Sacrifice this
 * artifact: Add one mana of any color."*
 *
 * The mana ability is the printed one exactly: a two-component cost (CR 602.1) of tapping **and**
 * sacrificing, adding one mana of any colour. Both components matter and neither is decoration — the
 * tap is what a summoning-sick permanent would not care about (a Treasure is not a creature) and the
 * sacrifice is what makes it one-shot.
 */
val treasureToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Treasure",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(TREASURE),
                powerToughness = null,
                definedColors = persistentSetOf(),
            ),
        manaAbilities =
            persistentListOf(
                ManaAbility(
                    options = ANY_COLOR,
                    cost = persistentListOf(ManaAbilityCost.TapSelf, ManaAbilityCost.SacrificeSelf),
                ),
            ),
    )

/**
 * The 4/1 black Skeleton with menace the Undercity's Catacombs creates (CR 111.4).
 *
 * **Its colour is *defined*, not derived** ([PrintedCharacteristics.definedColors]): a token has no mana
 * cost, so CR 202.2's derivation would call it colourless, and the creating effect says black
 * (CR 111.4). Nothing in the gauntlet asks this token its colour today, but the field exists precisely
 * so a token's printed colour is not quietly lost, and a 4/1 that a black-hating effect should catch is
 * exactly the case it was added for.
 *
 * **Menace is the card, not flavour**: a 4/1 dies to any single block, so the whole reason Catacombs is
 * worth entering is that one blocker cannot stop it (CR 702.110a).
 */
val undercitySkeletonToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Skeleton",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(SKELETON),
                powerToughness = PrintedPowerToughness(power = SKELETON_POWER, toughness = SKELETON_TOUGHNESS),
                keywords = persistentSetOf(Keyword.MENACE),
                definedColors = persistentSetOf(Color.BLACK),
            ),
    )

/** The index of Forge in [undercity]'s room list. */
private const val ROOM_FORGE: Int = 1

/** The index of Lost Well in [undercity]'s room list. */
private const val ROOM_LOST_WELL: Int = 2

/** The index of Trap! in [undercity]'s room list. */
private const val ROOM_TRAP: Int = 3

/** The index of Arena in [undercity]'s room list. */
private const val ROOM_ARENA: Int = 4

/** The index of Stash in [undercity]'s room list. */
private const val ROOM_STASH: Int = 5

/** The index of Archives in [undercity]'s room list. */
private const val ROOM_ARCHIVES: Int = 6

/** The index of Catacombs in [undercity]'s room list. */
private const val ROOM_CATACOMBS: Int = 7

/** The index of Throne of the Dead Three in [undercity]'s room list. */
private const val ROOM_THRONE: Int = 8

/**
 * *Secret Entrance* — "Search your library for a basic land card, reveal it, put it into your hand, then
 * shuffle." (CR 701.18)
 *
 * The whole room is the [LibrarySearch] clause, so the ability's ordinary effect is empty: the search
 * pauses for its find-one choice, which a [ResolutionEffect] could not make (ADR-004).
 */
private val secretEntrance: DungeonRoom =
    DungeonRoom(
        name = "Secret Entrance",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = TriggerZoneScope.Command,
                    librarySearch = LibrarySearch(find = LibrarySearchFilter.BASIC_LAND_CARD),
                ),
            ),
        successors = persistentListOf(ROOM_FORGE, ROOM_LOST_WELL),
    )

/**
 * *Forge* — "Put two +1/+1 counters on target creature." (CR 122.1, CR 115.1b)
 *
 * The pool's first **targeted room**, and the reason a room carries a whole [TriggeredAbility] rather
 * than a bare effect: the target is chosen as the room's ability is put on the stack (CR 603.3d) and
 * re-checked when it resolves (CR 608.2b), so a Forge whose creature dies in response does nothing.
 */
private val forge: DungeonRoom =
    DungeonRoom(
        name = "Forge",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            putCounters(
                                state,
                                onlyTargetPermanent(context.targets, "Forge"),
                                Counter.PLUS_ONE_PLUS_ONE,
                                FORGE_COUNTERS,
                            )
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_TRAP, ROOM_ARENA),
    )

/** *Lost Well* — "Scry 2." (CR 701.17a) The whole room is the private-look clause. */
private val lostWell: DungeonRoom =
    DungeonRoom(
        name = "Lost Well",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = TriggerZoneScope.Command,
                    libraryLook = LibraryLook(mode = LibraryLookMode.Scry(LOST_WELL_SCRY)),
                ),
            ),
        successors = persistentListOf(ROOM_ARENA, ROOM_STASH),
    )

/**
 * *Trap!* — "Target player loses 5 life." (CR 119.3, CR 115.1b)
 *
 * "Target **player**", not "target opponent": the venturing player may point it at themselves, which is
 * a legal and occasionally correct line and is exactly what [TargetSpec.TargetPlayer] enumerates. Life
 * *loss*, not damage (CR 119.3c) — nothing prevents it and no source deals it.
 */
private val trap: DungeonRoom =
    DungeonRoom(
        name = "Trap!",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    targetSpec = TargetSpec.TargetPlayer(),
                    effect =
                        ResolutionEffect { state, context ->
                            loseLife(state, onlyTargetPlayer(context.targets), TRAP_LIFE_LOSS)
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_ARCHIVES),
    )

/**
 * *Arena* — "Goad target creature." (CR 701.38a, CR 115.1b)
 *
 * The pool's only **attack requirement** and the reason `mtg-rules` grew one (`W11`): goad says the
 * creature *"attacks each combat if able"* until the goading player's next turn, which is a constraint
 * on the CR 508.1 declaration rather than anything about the creature. Composed from the published
 * [goad] verb, which records the requirement on the permanent; the engine publishes it on the
 * declare-attackers request and refuses a declaration that leaves the creature at home.
 *
 * **"Target creature", not "target creature an opponent controls"** — the venturing player may goad
 * their own, which is a legal line (it does nothing useful in a two-player game, since a creature that
 * must attack each combat is a creature you were probably attacking with, but it is a line the card
 * offers and [TargetSpec.TargetPermanent] enumerates). Goad's second half — *"attacks a player other
 * than you if able"* — is satisfied trivially at two seats whichever way the target points: there is
 * one defending player and it is either not the goader, or is, and "if able" then waives it.
 */
private val arena: DungeonRoom =
    DungeonRoom(
        name = "Arena",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            goad(state, onlyTargetPermanent(context.targets, "Arena"), context.controller)
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_ARCHIVES, ROOM_CATACOMBS),
    )

/** *Stash* — "Create a Treasure token." (CR 111.4) */
private val stash: DungeonRoom =
    DungeonRoom(
        name = "Stash",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect =
                        ResolutionEffect { state, context ->
                            createToken(state, context.controller, treasureToken)
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_CATACOMBS),
    )

/** *Archives* — "Draw a card." (CR 121.1) */
private val archives: DungeonRoom =
    DungeonRoom(
        name = "Archives",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, ARCHIVES_DRAW)
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_THRONE),
    )

/** *Catacombs* — "Create a 4/1 black Skeleton creature token with menace." (CR 111.4, CR 702.110a) */
private val catacombs: DungeonRoom =
    DungeonRoom(
        name = "Catacombs",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect =
                        ResolutionEffect { state, context ->
                            createToken(state, context.controller, undercitySkeletonToken)
                        },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = persistentListOf(ROOM_THRONE),
    )

/** *Throne of the Dead Three* — the last room. Unimplemented; the file header gives the diagnosis. */
private val throneOfTheDeadThree: DungeonRoom =
    DungeonRoom(
        name = "Throne of the Dead Three",
        ability =
            DungeonRoomAbility.Unimplemented(
                printed =
                    "Reveal the top ten cards of your library. Put a creature card from among them onto " +
                        "the battlefield with three +1/+1 counters on it. It gains hexproof until your " +
                        "next turn. Then shuffle.",
                diagnosis =
                    "four absent frameworks: LibraryReveal has no battlefield destination (CR 701.18) " +
                        "and no reveal has ever put a permanent onto the battlefield; 'with three +1/+1 " +
                        "counters' is a CR 614.1c enters-with-counters replacement, absent since Nyxborn " +
                        "Hydra; 'hexproof until your next turn' needs an EffectDuration the engine does " +
                        "not have; and 'then shuffle' returns the unchosen revealed cards to the library " +
                        "where every existing reveal puts them in a graveyard",
            ),
    )

/**
 * **Undercity** (CR 309) — the dungeon every "venture into Undercity" enters, and the only dungeon the
 * gauntlet prints. Nine rooms, verbatim from the card:
 *
 * ```
 * Secret Entrance — Search your library for a basic land card, reveal it, put it into your hand,
 *                   then shuffle. (Leads to: Forge, Lost Well)
 * Forge           — Put two +1/+1 counters on target creature. (Leads to: Trap!, Arena)
 * Lost Well       — Scry 2. (Leads to: Arena, Stash)
 * Trap!           — Target player loses 5 life. (Leads to: Archives)
 * Arena           — Goad target creature. (Leads to: Archives, Catacombs)
 * Stash           — Create a Treasure token. (Leads to: Catacombs)
 * Archives        — Draw a card. (Leads to: Throne of the Dead Three)
 * Catacombs       — Create a 4/1 black Skeleton creature token with menace.
 *                   (Leads to: Throne of the Dead Three)
 * Throne of the Dead Three — Reveal the top ten cards of your library. Put a creature card from among
 *                   them onto the battlefield with three +1/+1 counters on it. It gains hexproof until
 *                   your next turn. Then shuffle.
 * ```
 *
 * **The shape of the graph is the mechanic.** Four rooms branch and five do not, every path is exactly
 * five rooms long, and Archives and Catacombs are the two ways into the last room — so a run is four
 * binary-ish choices, not a track. That is what the CR 309.4 decision is for, and it is why the
 * successors are encoded rather than flattened into a chain.
 *
 * Two rooms are [DungeonRoomAbility.Unimplemented]; the file header above gives the full diagnosis for
 * each. Because of them this value must not be reachable from a registered card — `mtg-rules` fails
 * loudly on entering such a room rather than silently skipping it.
 */
val undercity: Dungeon =
    Dungeon(
        name = "Undercity",
        rooms =
            persistentListOf(
                secretEntrance,
                forge,
                lostWell,
                trap,
                arena,
                stash,
                archives,
                catacombs,
                throneOfTheDeadThree,
            ),
    )

/**
 * The single permanent a room targets (CR 115.1b). On resolution the target is still legal (CR 608.2b)
 * or the ability never resolved, so anything else here is an engine defect. [room] names the room in the
 * failure.
 */
private fun onlyTargetPermanent(
    targets: List<Target>,
    room: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $room targets exactly one creature, got $targets")

/** The single player Trap! targets (CR 115.1b), for [onlyTargetPermanent]'s reason. */
private fun onlyTargetPlayer(targets: List<Target>) =
    (targets.singleOrNull() as? Target.Player)?.id
        ?: error("CR 115.1b: Trap! targets exactly one player, got $targets")
