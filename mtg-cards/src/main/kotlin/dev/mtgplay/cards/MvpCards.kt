package dev.mtgplay.cards

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * The registry of every card `mtg-cards` defines so far. The P2.2 pool: the basic lands
 * [mountain], [forest], and [plains], and [lightningBolt]; the gauntlet Tranche 0 packet completes
 * the five basics with [island] and [swamp]. The P3.2 pool adds the first real
 * creatures (CR 302): [grizzlyBears], [hillGiant], [windDrake], [youthfulKnight], and
 * [standingTroops]. The P4.2 pool adds the seven real Bogles continuous-effect Auras (CR 303):
 * [rancor], [armadilloCloak], [cartoucheOfSolidarity], [sentinelsEyes], [etherealArmor],
 * [ancestralMask], and [abundantGrowth] — each encoded to its P4 static half (docs/design/layer-system.md).
 * The P5.3 pool adds the three real Bogles hexproof one-drops (CR 702.11): [gladecoverScout],
 * [slipperyBogle] (the first hybrid-cost card), and [silhanaLedgewalker] (with its blockable-only-by-flying
 * evasion). The P6.2b pool completes both MVP decklists: the Mono-Red Madness thirteen — [guttersnipe],
 * [sneakySnacker], [voldarenEpicure], [fieryTemper], [fireblast], [lavaDart], [grabThePrize],
 * [faithlessLooting], [meldedMoxite], and [highwayRobbery] (MadnessDeck.kt) — plus the remaining GW-Bogles
 * utility cards [utopiaSprawl], [malevolentRumble], and [ashBarrens] (BoglesUtility.kt). Their predefined
 * tokens ([bloodToken], [robotToken], [eldraziSpawnToken]) are created on demand and registered by the
 * create-token primitive, so — like [warriorToken] — they are not top-level registry entries. The P6.3
 * pool refreshes both decklists as they are now built: [kessigFlamebreather] for Mono-Red Madness, and
 * [kruphixsInsight], [wildGrowth], and [lifelink] — the *card* named Lifelink, an Aura — for GW Bogles.
 * The card-selection packet adds the gauntlet's look-at-the-top-and-draw family (CardSelection.kt):
 * [thoughtScour] and [mentalNote] (the mill cantrips), [lorienRevealed] (draw three, or islandcycle),
 * [unfathomableTruths] (the devoid draw-three plus an Eldrazi Spawn), and [pursueThePast] (gain two,
 * loot, flash back). The `FW-LIBLOOK` packet then encodes four of that family's six dropped siblings
 * (LibraryLookCards.kt): [brainstorm], [ponder], [preordain], and [impulse], each built on the private
 * look-and-arrange clause the family was waiting on (docs/design/library-look.md). The remaining two stay
 * absent rather than approximated: Winding Way needs a resolution-time choose-a-card-type mode
 * (`FW-MODAL`), and Lead the Stampede is a public *reveal* with a variable keep-all-matching, which wants
 * the CR 701.16 reveal path rather than the CR 701.14a look path.
 * The P8.4 pool broadens the gauntlet's mana bases with eight nonbasic lands (NonbasicLands.kt): the
 * Mirrodin artifact lands [greatFurnace], [seatOfTheSynod], and [vaultOfWhispers]; the four
 * enters-tapped indestructible Bridges [drossforgeBridge], [mistvaultBridge], [silverbluffBridge], and
 * [slagwoodsBridge]; and [idyllicBeachfront], whose two mana abilities come from its Plains Island type
 * line (CR 305.6).
 *
 * The gauntlet Tier-0 packet adds twelve more: the damage family (GauntletBurn.kt) [gutShot] (the
 * pool's first all-Phyrexian cost), [galvanicBlast] (metalcraft as a state-dependent amount), and the
 * two sweepers [breathWeapon] and [endTheFestivities]; the lifegain family (GauntletLifegain.kt)
 * [healerOfTheGlade], [outlawMedic] (lifelink plus a dies trigger), [gnawToTheBone],
 * [unionOfTheThirdPath], [spinewoodsPaladin] (trample, an enters trigger, and plot), and [wellwisher];
 * the Aura [spiritLink] (Auras.kt), which completes the lifegain trio with [armadilloCloak] and
 * [lifelink]; and [murmuringMystic] (MurmuringMystic.kt), whose [birdIllusionToken] — like
 * [bloodToken], [robotToken], [eldraziSpawnToken], and [warriorToken] — is created on demand rather
 * than being a top-level registry entry.
 * [kruphixsInsight], [wildGrowth], and [lifelink] — the *card* named Lifelink, an Aura — for GW Bogles. The
 * `FW-ABILTGT` packet adds [lotlethGiant], the first card whose *ability* targets
 * (docs/design/targeted-abilities.md §7). The snow packet adds the four cards of Jeskai Ephemerate's
 * snow half (Snow.kt): the Snow-Covered basics [snowCoveredIsland], [snowCoveredMountain], and
 * [snowCoveredPlains] — CR 205.4a two-supertype basics — and [skred], the first card to target a
 * creature and nothing else, whose damage counts snow permanents at resolution (CR 608.2).
 *
 * The colourless-utility packet adds four gauntlet cards whose whole printed text the published
 * vocabulary already covers: [glacialFloodplain] and [volatileFjord] (Snow.kt), the two snow dual
 * lands that are [idyllicBeachfront] plus `Supertype.SNOW`; and, in ColorlessArtifacts.kt,
 * [ichorWellspring] — an enters-**or**-dies draw, one printed ability carrying two conditions — and
 * [expeditionMap], the first client of the packet's one new primitive,
 * `LibrarySearchFilter.LAND_CARD`. Bonder's Ornament and Haunted Fengraf were written and dropped:
 * each would be the pool's first permanent that is both a mana source and the source of a
 * `{T}`-costed activated ability with a mana component, and payment enumeration offers a plan that
 * taps it for mana and then cannot pay its own `{T}` (see that file's header and the packet report).
 *
 * The `P-MANASICK` packet adds the pool's first **creature** mana sources (ManaCreatures.kt):
 * [elvishMystic] and [fyndhornElves], the two `{T}`: Add `{G}` Elf Druids. They are the first
 * objects that make the CR 302.6 summoning-sickness restriction observable from mana payment, and
 * they landed together with the gate that enforces it (`manaSourceUsable`).
 *
 * The removal-and-destruction packet adds the gauntlet's first targeted answers (Removal.kt):
 * [castDown] and [terminate] (destroy a creature), [smashToSmithereens] and [ancientGrudge] (destroy
 * an artifact, the second with flashback), [scourFromExistence] (exile any permanent), and
 * [lastBreath] (exile a small creature and give its controller life). They are the first clients of
 * the CR 701.7a destroy and CR 701.3a exile effect primitives and of "target &lt;permanent&gt;"
 * (`TargetSpec.TargetPermanent`). Two siblings are deliberately absent: Raze needs a sacrifice
 * *additional cost* — a new enumerated decision — and Cryoshatter needs trigger conditions for a
 * permanent becoming tapped or being dealt damage, which nothing in the engine watches for.
 *
 * The `FW-CLAUSEHOOK` packet adds the first two cards whose *ability* carries a post-resolution clause
 * (LibraryLookCreatures.kt): [faerieSeer], whose enters-the-battlefield trigger scries 2, and
 * [seaGateOracle], whose looks at two and mandatorily keeps one. Both declare exactly the
 * [dev.mtgplay.core.definition.LibraryLook] values [preordain] and [impulse] already declared — the packet
 * lifted the four clauses off `SpellDefinition` onto a carrier a triggered or activated ability implements
 * too (docs/design/resolution-clause-hook.md), so the cards needed no new engine mechanism at all. Three
 * siblings from the same triage row stay absent, each on a *different* missing framework: Lembas needs
 * `FW-SHUFFLEIN`, Conduit Pylons needs surveil (CR 701.44) plus `FW-MANA`, and Giant's Boulder — a scry 2
 * card, not the surveil card it is sometimes filed as — needs `FW-MANA` and a targeted destroy ability.
 * The `FW-COUNTER` packet adds the gauntlet's eight pure counters (Counters.kt): [counterspell],
 * [dispel], [negate], [annul], [envelop], and [removeSoul], plus the two unless-pay counters
 * [forceSpike] and [spellPierce]. They are the first cards to target a **spell on the stack**
 * (`TargetSpec.SpellOnStack`) and the first clients of the CR 701.5a counter primitive; [forceSpike]
 * and [spellPierce] additionally open the first decision this engine puts to someone other than the
 * resolving spell's controller (CR 118.3a). Their siblings are deliberately absent: the Blasts and Steel
 * Sabotage need modes (CR 700.2), Prohibit needs kicker (CR 702.33), and Spellstutter Sprite needs a
 * *triggered* ability with a dynamic target restriction (docs/design/countering-spells.md §11).
 * The `FW-MANA` packet adds the pool's first cards whose mana abilities add a **board-dependent
 * amount** (CR 605.2): Monster Tron's three Urza lands (UrzaLands.kt) [urzasMine], [urzasPowerPlant],
 * and [urzasTower] — one colorless alone, two or (for the Tower) three with the other two out — and
 * [priestOfTitania] (ManaCreatures.kt), one `{G}` per Elf on the battlefield. Two of that framework's
 * cards were left absent at the time; Saruli Caretaker still is, its ability costing "{T}, Tap an
 * untapped creature you control" — an activation cost shape the payment model cannot express
 * (docs/design/mana-payment.md §9).
 * The `FW-COUNTERS` packet adds counters on permanents (CR 122) and the three keywords the engine
 * lacked — haste (CR 702.10), defender (CR 702.3) and reach (CR 702.17), each with its rules effect,
 * never a bare enum member. It brings two cards. [overgrownBattlement] (ManaCreatures.kt) is the
 * `FW-MANA` card that was blocked *only* on `Keyword.DEFENDER` existing; with the keyword real and
 * `PermanentFilter` widened by a card-type and a keyword axis, "add {G} for each creature you control
 * with defender" is now exact. [unexpectedFangs] (CountersOnPermanents.kt) is the pool's first card to
 * place counters at all, and the first to place a **keyword counter** (CR 122.1b). The packet's other
 * seven cards stay absent, each on a framework it does not own: Wall of Roots and Gingerbrute need
 * mana-ability/activation cost shapes and a per-turn limiter, Kenku Artificer needs CR 613 layers 4
 * and 7b, Nyxborn Hydra needs `{X}` and bestow, Writhing Chrysalis needs two absent trigger
 * conditions, Clockwork Percussionist needs a delayed play-permission window, and Goblin Tomb Raider
 * needs a *conditional* static continuous effect that affects its own source.
 *
 * The `FW-COST` packet adds the pool's first spells whose **total cost is not their printed cost**
 * (CR 601.2f, docs/design/cost-modification.md): Grixis Affinity's [myrEnforcer], [thoughtcast] and
 * [utromMonitor] (affinity for artifacts, CR 702.41a), Mono-Blue Terror's [crypticSerpent] (a
 * graveyard count, floored at `{U}{U}` by CR 118.7a), and Mono Blue Faeries' [ofOneMind] (a flat `{2}`
 * gated on a two-part board condition). Five siblings from the same triage rows stay absent, each on a
 * framework that packet does not own: **Tolarian Terror** prints Cryptic Serpent's clause plus **ward
 * {2}** (CR 702.21a, a triggered ability — `FW-WARD`), **Refurbished Familiar** and **Deem Inferior**
 * both need a decision put to a player who is not the spell's controller (`FW-NONCTRLDEC`, and Deem
 * Inferior additionally needs cards-drawn-this-turn tracking the state does not keep), **Ride's End**
 * prices off its chosen target while cast legality is decided before targets exist (`FW-TGTCOND`), and
 * **Sunscape Familiar** — whose other-object reducer the framework does implement and test — prints
 * **Defender**, the same missing keyword (`FW-DEFENDERKW`) that keeps Overgrown Battlement out.
 *
 * The `P-SEARCH` / `FW-SHUFFLEIN` packet widens the CR 701.18 library search on two axes and brings six
 * cards (docs/design/library-search.md). The Landscape cycle — [contaminatedLandscape],
 * [twistedLandscape], and [perilousLandscape] (Landscapes.kt) — needs both: a **battlefield-tapped**
 * destination and a filter that is a Basic supertype *and* one of three land types. They are also the
 * pool's first **plain** cycling (CR 702.29a, "draw a card"), which was composable all along but had no
 * client, because Ash Barrens and Lórien Revealed both print typecycling instead. In
 * LibrarySearchCards.kt, [generousEnt] is the second typecycler and the first to name a land type other
 * than Island; [cropRotation] is the first **spell** whose resolution is a search at all, which is what
 * moved the search clause off `ActivatedAbility` onto the `ResolutionClauses` carrier; and [lembas] is
 * the first card to move a card from a graveyard *back into a library*, on the new
 * `shuffleIntoOwnersLibrary` primitive. Two siblings stay absent: **Land Grant**'s "reveal your hand
 * rather than pay" is an alternative cost that is both conditional on a hidden zone and paid by
 * revealing (`FW-ALTCOST`), and **Troll of Khazad-dûm**'s "can't be blocked except by three or more
 * creatures" is a constraint over the whole block declaration while `DeclareBlockers` enumerates
 * pairwise (`FW-BLOCKSET`). Each is one framework — and zero primitives — away.
 * The `FW-MODAL` packet adds the gauntlet's modal instants (Blasts.kt): [blueElementalBlast] and
 * [redElementalBlast], [hydroblast] and [pyroblast], and [steelSabotage]. They are the first cards with
 * modes at all (CR 700.2), so they are the first to exercise the CR 601.2b mode stage that had been a
 * documented no-op since P2.1 — and the ordering matters rather than merely existing, because every one
 * of them has two modes that target *different kinds of object*, so target enumeration is undefined
 * until the mode is settled. The four Blasts are deliberately **two** templates and not one
 * (docs/design/countering-spells.md §1.2): the Elemental Blasts restrict their *target* and vanish from
 * enumeration with no object of the hosed colour in play, while Hydroblast and Pyroblast restrict their
 * *effect* and stay castable against anything — encoding the second as the first would be a silent
 * ADR-005 enumeration gap. The packet added [dev.mtgplay.core.definition.ModalSpell] and
 * [dev.mtgplay.core.definition.SpellMode] in core, two colour members on
 * [dev.mtgplay.core.definition.PermanentRestriction], and two primitives in `mtg-rules`: the published
 * colour predicate `targetIsColor` (the effect-side twin of the targeting restrictions) and
 * `returnPermanentToOwnersHand` (the battlefield bounce Steel Sabotage needs — the design note wrongly
 * expected the existing graveyard `returnToOwnersHand` to serve). Two of the packet's cards stay absent,
 * both on the same missing framework: **Cast into the Fire**'s first mode deals damage to "each of up to
 * two target creatures" and **Thraben Charm**'s third exiles "any number of target players'
 * graveyards" — both variable-count multi-target lines (`FW-MULTITGT`), and a modal card whose modes
 * cannot all be offered is an enumeration gap rather than a partial card.
 *
 * [definitions] is shaped for direct `MatchConfig.definitions` consumption: the engine carries
 * it into `GameState` in canonical name-sorted order regardless of this map's own order
 * (ADR-009 — definitions ride in the state; a [CardRef] without an entry is inert). The pool
 * grows card by card through Phase 6; each addition is a definition file plus one entry here.
 *
 * The `FW-MULTITGT` packet adds the pool's first **multi-target** cards (MultiTargets.kt):
 * [faerieMacabre], whose hand-scoped "Discard this card:" ability exiles up to two target cards from
 * graveyards, and [bloodFountain], whose sacrifice ability returns up to two target creature cards from
 * its controller's graveyard. Both are cards `FW-ZONETGT` recorded as blocked on a target *count*
 * (docs/design/multi-target.md). It also adds the two control-restricted targeting cards
 * (ControlledTargets.kt) that the two new [dev.mtgplay.core.definition.PermanentRestriction] members
 * land with no further framework work: [tamiyosSafekeeping] ("target permanent you control" gains
 * hexproof and indestructible) and [brinebarrowIntruder] (flash, and "target creature an opponent
 * controls" gets -2/-0). Rooftop Percher and Call Damage Control stay absent — changeling and modality
 * are frameworks this packet does not own.
 */
object MvpCards {
    /** Every defined card, keyed by its printed-name [CardRef] (CR 201). */
    val definitions: Map<CardRef, CardDefinition> =
        listOf(
            abundantGrowth,
            ancestralMask,
            ancientGrudge,
            annul,
            archaeomancer,
            armadilloCloak,
            ashBarrens,
            bloodFountain,
            blueElementalBlast,
            brainstorm,
            breathWeapon,
            brinebarrowIntruder,
            cartoucheOfSolidarity,
            castDown,
            contaminatedLandscape,
            counterspell,
            cropRotation,
            crypticSerpent,
            dispel,
            drossforgeBridge,
            elvishMystic,
            endTheFestivities,
            envelop,
            etherealArmor,
            evisceratorsInsight,
            expeditionMap,
            faerieMacabre,
            faerieSeer,
            faithlessLooting,
            fieryTemper,
            fireblast,
            forceSpike,
            forest,
            fyndhornElves,
            galvanicBlast,
            generousEnt,
            gingerbreadCabin,
            glacialFloodplain,
            gladecoverScout,
            gnawToTheBone,
            grabThePrize,
            greatFurnace,
            grizzlyBears,
            gutShot,
            guttersnipe,
            healerOfTheGlade,
            highwayRobbery,
            hillGiant,
            ichorWellspring,
            idyllicBeachfront,
            hydroblast,
            impulse,
            island,
            kessigFlamebreather,
            krarkClanShaman,
            kruphixsInsight,
            lastBreath,
            lavaDart,
            lembas,
            lifelink,
            lightningBolt,
            lorienRevealed,
            lotlethGiant,
            makeshiftMunitions,
            malevolentRumble,
            meldedMoxite,
            mentalNote,
            mistvaultBridge,
            mountain,
            murmuringMystic,
            myrEnforcer,
            negate,
            ofOneMind,
            outlawMedic,
            overgrownBattlement,
            perilousLandscape,
            plains,
            ponder,
            pyroblast,
            preordain,
            priestOfTitania,
            pulseOfMurasa,
            pursueThePast,
            rancor,
            reckonersBargain,
            redElementalBlast,
            removeSoul,
            scourFromExistence,
            seaGateOracle,
            seatOfTheSynod,
            sentinelsEyes,
            silhanaLedgewalker,
            silverbluffBridge,
            skred,
            slagwoodsBridge,
            slipperyBogle,
            smashToSmithereens,
            sneakySnacker,
            snowCoveredIsland,
            snowCoveredMountain,
            snowCoveredPlains,
            spellPierce,
            spinewoodsPaladin,
            steelSabotage,
            spiritLink,
            standingTroops,
            swamp,
            tamiyosSafekeeping,
            terminate,
            thoughtcast,
            thoughtScour,
            timberwatchElf,
            twistedLandscape,
            unexpectedFangs,
            unfathomableTruths,
            unionOfTheThirdPath,
            urzasMine,
            urzasPowerPlant,
            urzasTower,
            utopiaSprawl,
            utromMonitor,
            vaultOfWhispers,
            volatileFjord,
            voldarenEpicure,
            wellwisher,
            wildGrowth,
            windDrake,
            youthfulKnight,
        ).associateBy { CardRef(it.characteristics.name) }
}
