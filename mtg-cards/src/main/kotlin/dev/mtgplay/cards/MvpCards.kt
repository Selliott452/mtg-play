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
 * cards are deliberately absent: Overgrown Battlement counts creatures **with defender**, a keyword
 * that does not exist yet (`FW-DEFENDERKW`), and Saruli Caretaker's ability costs "{T}, Tap an
 * untapped creature you control" — an activation cost shape the payment model cannot express
 * (docs/design/mana-payment.md §9).
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
 * [definitions] is shaped for direct `MatchConfig.definitions` consumption: the engine carries
 * it into `GameState` in canonical name-sorted order regardless of this map's own order
 * (ADR-009 — definitions ride in the state; a [CardRef] without an entry is inert). The pool
 * grows card by card through Phase 6; each addition is a definition file plus one entry here.
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
            brainstorm,
            breathWeapon,
            cartoucheOfSolidarity,
            castDown,
            counterspell,
            crypticSerpent,
            dispel,
            drossforgeBridge,
            elvishMystic,
            endTheFestivities,
            envelop,
            etherealArmor,
            expeditionMap,
            faerieSeer,
            faithlessLooting,
            fieryTemper,
            fireblast,
            forceSpike,
            forest,
            fyndhornElves,
            galvanicBlast,
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
            impulse,
            island,
            kessigFlamebreather,
            kruphixsInsight,
            lastBreath,
            lavaDart,
            lifelink,
            lightningBolt,
            lorienRevealed,
            lotlethGiant,
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
            plains,
            ponder,
            preordain,
            priestOfTitania,
            pulseOfMurasa,
            pursueThePast,
            rancor,
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
            spiritLink,
            standingTroops,
            swamp,
            terminate,
            thoughtcast,
            thoughtScour,
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
