package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CounterAmount
import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.EntersWithCounters
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealDisposition
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.goad
import dev.mtgplay.rules.effect.loseLife
import dev.mtgplay.rules.effect.putCounters
import dev.mtgplay.rules.effect.takeTheInitiative
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * **The Undercity** (CR 309) and the two gauntlet cards that walk you into it (`W10-A`, finished by
 * `W11`).
 *
 * The initiative is the largest single mechanic the gauntlet prints. `mtg-rules` implements all of
 * it — the designation (CR 701.51a), the upkeep venture (CR 701.51b), the combat-damage handover
 * (CR 701.51c), and the venture keyword action with its CR 309.4 branch decision — and this file is the
 * card half: the one dungeon a "take the initiative" effect enters, encoded room by room from the
 * printed card, plus [avengingHunter] and [goliathPaladin], the two bodies that print the line.
 *
 * ---
 *
 * **All nine rooms run.** `W10-A` shipped seven and recorded two as
 * [DungeonRoomAbility.Unimplemented] — Arena and Throne of the Dead Three — with the reasoning that a
 * room silently doing nothing would be the plausible-looking wrong card PLAN.md §7 forbids, and that
 * the cards must therefore stay unregistered rather than walk into a blank. Every path through the
 * dungeon passes Arena or reaches Throne, so there was no route around either. `W11` built what the two
 * were waiting for and the room list has no gap left:
 *
 * 1. **Arena — "Goad target creature."** Goad (CR 701.38a) is *"until your next turn, that creature
 *    attacks each combat if able and attacks a player other than you if able"* — the engine's first
 *    **attack requirement** (CR 508.1d) and, before `W11`, the only rule in the pool that had to make
 *    the declare-attackers declaration something other than a free subset of the eligible creatures.
 *    That is a change to the enumerated action space (ADR-005), not an effect: the request publishes
 *    the requirement and the validator enforces it. The second half constrains nothing at two seats and
 *    is recorded rather than dropped (see [dev.mtgplay.rules.effect.goad]).
 * 2. **Throne of the Dead Three** — four printed clauses that `W11` turned into four axes of one
 *    [LibraryReveal]: a battlefield [RevealDisposition] whose unchosen cards stay in the library and
 *    are shuffled back into obscurity, a **mandatory** keep (an instruction with no legal decline must
 *    not enumerate one), a CR 614.1c enters-with-counters replacement created by the *effect* rather
 *    than by the entering card, and a layer-6 keyword grant on the duration goad also uses.
 *
 * Three of the four Throne blockers `W10-A` recorded had in fact been built by later packets and only
 * needed reaching for: [EntersWithCounters] landed with `W10-C`, [Keyword.HEXPROOF] and the layer-6
 * `grantedKeywords` seam with `W9-E`, and [RevealedCardFilter.CREATURE_CARD] earlier still. What was
 * genuinely new was a reveal that ends on the **battlefield**, a "then shuffle" over the cards a reveal
 * did *not* take, and the shared duration.
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

/** Throne of the Dead Three's "Reveal the top **ten** cards" (CR 701.16). */
private const val THRONE_REVEAL: Int = 10

/** Throne of the Dead Three's "with **three** +1/+1 counters on it" (CR 614.1c). */
private const val THRONE_COUNTERS: Int = 3

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

/**
 * *Throne of the Dead Three* — the Undercity's last room and its payoff (CR 309.6): "Reveal the top ten
 * cards of your library. Put a creature card from among them onto the battlefield with three +1/+1
 * counters on it. It gains hexproof until your next turn. Then shuffle."
 *
 * The whole room is one [LibraryReveal] clause, so the ability's ordinary effect is empty: the choice
 * among the revealed creature cards is a mid-resolution decision a [ResolutionEffect] could not make
 * (ADR-004). Four printed clauses, each an axis of that one clause:
 *
 * - **"Put a creature card"**, not "you may put": [LibraryReveal.mandatory], so the engine stops
 *   offering a decline once a creature card has been revealed. With none among the ten nothing is put
 *   anywhere, which is CR 608.2's "do as much as you can" rather than a violation.
 * - **"with three `+1/+1` counters on it"** is a CR 614.1c replacement of the *entering event*, so the
 *   creature is a three-counter creature the first time anything looks at it — including the CR 704.5f
 *   check. [CounterAmount.Fixed]'s first client; every self-replacement in the gauntlet announces X.
 * - **"It gains hexproof until your next turn"** — a CR 611.2 continuous effect on the permanent that
 *   just entered, with the duration `W11` added for it and for Arena's goad.
 * - **"Then shuffle"** is the reason the unchosen nine matter: they were revealed and never moved, so
 *   without the shuffle the revealer would know their next nine draws.
 */
private val throneOfTheDeadThree: DungeonRoom =
    DungeonRoom(
        name = "Throne of the Dead Three",
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = TriggerZoneScope.Command,
                    libraryReveal =
                        LibraryReveal(
                            count = THRONE_REVEAL,
                            toHand = RevealedCardFilter.CREATURE_CARD,
                            toHandCount = 1,
                            disposition = RevealDisposition.CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED,
                            mandatory = true,
                            entersWithCounters =
                                EntersWithCounters(
                                    counter = Counter.PLUS_ONE_PLUS_ONE,
                                    amount = CounterAmount.Fixed(THRONE_COUNTERS),
                                ),
                            grantedUntilYourNextTurn = persistentSetOf(Keyword.HEXPROOF),
                        ),
                ),
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
 * **No room is [DungeonRoomAbility.Unimplemented] any more** (`W11`), which is what makes this value
 * reachable from a registered card at all: `mtg-rules` fails loudly on entering such a room rather than
 * silently skipping it, and [Dungeon.unimplementedRooms] being empty is pinned in `InitiativeSpec`.
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
 * The two gauntlet bodies that walk you into the Undercity print one line and it is the same line
 * (CR 701.51a): *"When this creature enters, you take the initiative."*
 *
 * The trigger is an ordinary CR 603.6a enters-the-battlefield ability, and its effect is the published
 * [takeTheInitiative] verb with [undercity] handed in — the one place a card names the dungeon
 * (ADR-003). What the effect returns is a state with the *venture* trigger pending, so the opponent
 * gets a priority window between the creature arriving and the room's ability resolving; the CR 309.4
 * branch choice happens one resolution later, where the engine can pause for it (ADR-004).
 */
private val takeTheInitiativeOnEntry: TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = ResolutionEffect { state, context -> takeTheInitiative(state, context.controller, undercity) },
    )

/**
 * Avenging Hunter — `{4}{G}` Creature — Dragon Ranger, a 5/4. "Trample. When this creature enters,
 * you take the initiative." The Elves deck's four-of payoff and its only card that ventures.
 *
 * **The type line is the snapshot's, and a packet briefly changed it to Elf Ranger.** The packet brief
 * listed this card as "5/4 trample, Elves" — naming the *deck*, in parallel with "Goliath Paladin
 * (3/6 vigilance, Jeskai sideboard)" — and that was misread as a type line. The reasoning built on
 * top of the misreading was sound and is worth keeping as a warning: Elves plays this alongside Priest
 * of Titania, Timberwatch Elf and Wellwisher, every one of which counts Elves on the battlefield
 * (CR 205.3m), so if the card *were* an Elf, encoding it as a Dragon would silently produce less mana,
 * a smaller pump and less life.
 *
 * But the snapshot is the repo's pinned authority and a coordinator's parenthetical is not evidence
 * against it, so the subtypes are the snapshot's. If the printed card really does read Elf Ranger,
 * the fix belongs in the snapshot with a source — and the ingestion that produced it should be
 * re-run rather than hand-edited, because a hand-edited snapshot is no longer a snapshot of anything.
 *
 * Trample is printed and does the work the initiative wants (CR 702.19b): the initiative changes hands
 * when an opponent deals **combat damage to the initiative holder** (CR 701.51c), so a 5/4 that pushes
 * damage through a chump block is a 5/4 that keeps it.
 */
val avengingHunter: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Avenging Hunter",
                manaCost = ManaCost.parse("{4}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Dragon"), Subtype("Ranger")),
                powerToughness = PrintedPowerToughness(power = 5, toughness = 4),
                keywords = persistentSetOf(Keyword.TRAMPLE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities = persistentListOf(takeTheInitiativeOnEntry)
    }

/**
 * Goliath Paladin — `{4}{W}` Creature — Giant Knight, a 3/6. "Vigilance. When this creature enters,
 * you take the initiative." Jeskai Ephemerate's sideboard, and the last card of the gauntlet.
 *
 * **Vigilance is the initiative card here, not a keyword tacked on** (CR 702.21b, CR 701.51c): the
 * initiative passes to an opponent who deals combat damage to its holder, so the holder's problem is
 * being able to attack *and* still have a blocker back. A 3/6 that does not tap to attack is exactly
 * that, and it is why this body rather than a bigger one is the white initiative common.
 */
val goliathPaladin: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Goliath Paladin",
                manaCost = ManaCost.parse("{4}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Giant"), Subtype("Knight")),
                powerToughness = PrintedPowerToughness(power = 3, toughness = 6),
                keywords = persistentSetOf(Keyword.VIGILANCE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities = persistentListOf(takeTheInitiativeOnEntry)
    }

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
