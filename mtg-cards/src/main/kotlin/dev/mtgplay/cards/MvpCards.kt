package dev.mtgplay.cards

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.InterveningIf
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
 * look-and-arrange clause the family was waiting on (docs/design/library-look.md).
 *
 * The `W7-C` packet then adds the **filtered** look (RevealAndBottom.kt): [ancientStirrings],
 * [augurOfBolas], and [leadTheStampede], each a "look, keep what matches, bottom the rest" clause on the
 * one mode `FW-LIBLOOK` left open, and between them the packet's three new
 * [dev.mtgplay.core.definition.RevealedCardFilter] members. It also adds the two graveyard lands
 * (GraveyardHate.kt): [bojukaBog], whose enters-the-battlefield trigger exiles a target player's whole
 * graveyard — the first encoded land with a trigger at all, and so the first card that can observe the
 * triage's T18 played-land fix — and [hauntedFengraf], whose `{3}`, `{T}`, sacrifice ability returns a
 * creature card chosen by the match-owned PRNG (ADR-006). Winding Way is the family's last absentee and
 * its blocker is *not* `FW-MODAL`, which has landed: its card-type choice happens as the spell resolves,
 * not when a modal spell's modes are chosen at CR 601.2b (CardSelection.kt records what it needs).
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
 * `LibrarySearchFilter.LAND_CARD`. Bonder's Ornament and Haunted Fengraf were written and dropped at the
 * time: each would be the pool's first permanent that is both a mana source and the source of a
 * `{T}`-costed activated ability with a mana component, and payment enumeration offered a plan that
 * tapped it for mana and then could not pay its own `{T}`. `FW-MANACOST` fixed that, and [hauntedFengraf]
 * has since landed (GraveyardHate.kt), and `W8-G` has landed [bondersOrnament] too — the sentence that
 * stood here, that it "still needs add one mana of any color", was false when it was written:
 * [giantsBoulder] had been declaring that exact production shape in the same file since `FW-MANACOST`.
 *
 * The `W8-G` packet adds the three gauntlet cards that belong to no family (AwkwardSingles.kt), each of
 * which had been picked up and put down at least once before: [stonehornDignitary], whose "target opponent
 * skips their next combat phase" is the CR 500.10 phase-skip framework (one field on `PlayerState`, one
 * clause in `isSkipped`, one decrement); [standardBearer], the pool's first CR 601.2c targeting
 * *requirement*, which narrows what an **opponent** may announce rather than what is legal; and
 * [sewerVeillanceCam], whose "you may tap or untap target creature" is **not** modal (CR 700.2) and needed
 * a three-way resolution clause rather than the `FW-MODAL` its earlier drop named. Kenku Artificer, Sacred
 * Cat and Inventor's Axe stay absent, with their diagnoses in AwkwardSingles.kt's header.
 *
 * The keyword-tail packet adds the last four gauntlet cards whose blocker was a missing keyword or a
 * missing framework half (KeywordTailCards.kt): [toxinAnalysis], which **grants** the pool's first
 * [dev.mtgplay.core.card.Keyword.DEATHTOUCH] (CR 702.2) and investigates for a [clueToken];
 * [rooftopPercher], the pool's only [dev.mtgplay.core.card.Keyword.CHANGELING] (CR 702.73);
 * [goblinTombRaider], the first **conditional** static ability (`FW-CONDSTATIC`, CR 604.3 — "as long
 * as you control an artifact"); and [gingerbrute], whose `{1}` ability grants itself the pool's first
 * **granted** evasion (CR 509.1b). Its [clueToken] — like [bloodToken], [robotToken],
 * [eldraziSpawnToken], [warriorToken] and [foodToken] — is created on demand rather than being a
 * top-level registry entry. Clockwork Percussionist was written and dropped: its dies trigger grants a
 * *play permission* lasting "until the end of your next turn", which is neither a continuous effect
 * nor a single-turn duration (docs/design/duration.md §12 names both exclusions).
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
 * (`TargetSpec.TargetPermanent`). One sibling is still absent: Cryoshatter needs trigger conditions
 * for a permanent becoming tapped or being dealt damage, which nothing in the engine watches for.
 * (Raze was listed here too until `P-ABILSOURCE`; it is now in LandDestruction.kt.)
 *
 * The `FW-CLAUSEHOOK` packet adds the first two cards whose *ability* carries a post-resolution clause
 * (LibraryLookCreatures.kt): [faerieSeer], whose enters-the-battlefield trigger scries 2, and
 * [seaGateOracle], whose looks at two and mandatorily keeps one. Both declare exactly the
 * [dev.mtgplay.core.definition.LibraryLook] values [preordain] and [impulse] already declared — the packet
 * lifted the four clauses off `SpellDefinition` onto a carrier a triggered or activated ability implements
 * too (docs/design/resolution-clause-hook.md), so the cards needed no new engine mechanism at all. Three
 * siblings from the same triage row stay absent, each on a *different* missing framework: Lembas needs
 * `FW-SHUFFLEIN`, Conduit Pylons needs surveil (CR 701.44), and Giant's Boulder — a scry 2 card, not the
 * surveil card it is sometimes filed as — needs a "target permanent" restriction. `FW-MANACOST` supplied
 * the mana half both were also waiting on, so each is now blocked on exactly one thing.
 * The `FW-MANACOST` packet adds the pool's first mana sources whose ability costs something other than
 * `{T}` or "sacrifice this" (CostedManaSources.kt): [saruliCaretaker] ("{T}, Tap an untapped creature you
 * control"), [wallOfRoots] ("Put a -0/-1 counter on this creature", once each turn) and
 * [barrelsOfBlastingJelly] ("{1}:", once each turn). Each exercises a different half of the payment
 * *capacity* problem — a budget outside the source classes, a cost that bounds nothing, and a cost that
 * makes an activation a consumer as well as a producer (docs/design/mana-payment.md §11).
 *
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
 * `W7-C` re-checked Thraben Charm against its oracle text and found the count to be only *half* its
 * blocker. Its third mode needs `TargetSpec.TargetPlayer` to gain a cardinality — it is a `data object`
 * with `count` hard-wired to `TargetCount.ONE`, so the noun cannot be asked for more than one player — and
 * its **second** mode ("Destroy target enchantment") needs a `PermanentRestriction` member that does not
 * exist. The whole-graveyard exile its third mode also wants has since landed
 * (`dev.mtgplay.rules.effect.exileGraveyard`, GraveyardHate.kt), so the exile is no longer part of the gap.
 *
 * The `FW-X` / `FW-OPTCOST` / `FW-ALTCOST` packet adds the pool's first cards with an **optional**
 * cost (OptionalCostCards.kt): [goblinBushwhacker] and [prohibit], the two kickers (CR 702.33), and
 * [landGrant], whose alternative cost is "reveal your hand rather than pay" (CR 118.9). The two kickers
 * are deliberately different halves of the same keyword — Bushwhacker reads "was it kicked" back from a
 * *permanent*, through CR 702.33f's linked information and a CR 603.4 intervening-if, while Prohibit
 * reads it during its own resolution off its own cast record. Land Grant was written and dropped twice
 * before: `P-SEARCH` had its search half and could not express a permission that was both conditional
 * on a hidden zone and paid by revealing.
 *
 * Two of that packet's five cards stay absent, each on a framework it does not own. **Kaervek's Torch**
 * prints "As long as this is on the stack, spells that target it cost {2} more to cast" — a cost
 * *increase*, which `FW-COST` leaves deliberately unrepresentable, and one keyed on *another* spell's
 * chosen targets, so pricing it would also have to filter target enumeration by affordability
 * (`Targets.kt`). **Nyxborn Hydra** needs bestow (`FW-BESTOW`) and a CR 614.1c "enters with X +1/+1
 * counters" replacement; the counters framework it is sometimes filed under supplies neither. The
 * variable-cost framework itself shipped without a pool card for that reason and is carried by rules
 * fixtures, the way `FW-COST` and the cast-from-elsewhere permissions already are.
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
 *
 * The **unblocked-cards** packet then encodes the five cards earlier packets had written, diagnosed, and
 * dropped, every one of them on a framework that has since landed. [ghostlyFlicker] (ExileAndReturn.kt)
 * is the pool's first card with an **exact** count above one, so it is the first whose targeting minimum
 * decides castability at all — it is absent from the priority window with only one legal permanent.
 * [castIntoTheFire] and [thrabenCharm] (ModalInstants.kt) are the two `FW-MODAL` cards whose modes carry
 * their own counts; the Charm's third brings [dev.mtgplay.core.definition.TargetCount.AnyNumber], the
 * **unbounded** count, and the first count on [dev.mtgplay.core.definition.TargetSpec.TargetPlayer].
 * [giantsBoulder] (ColorlessArtifacts.kt) and [basiliskGate] (NonbasicLands.kt) are the two cards trap
 * **T17** kept out — each is both a mana source and the source of a `{T}`-costed ability with a mana
 * component — and `FW-MANA`'s payment reservation is what makes them encodable with no card-side
 * workaround. Two of the standing diagnoses turned out to be **wrong**: Giant's Boulder was filed as
 * needing a "target permanent" restriction that [scourFromExistence] had already shipped, and Basilisk
 * Gate's was never the restriction either. The packet adds two
 * [dev.mtgplay.core.definition.PermanentRestriction] members (`ENCHANTMENT` and the pool's first
 * disjunctive one, `ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL`) and two `mtg-rules` primitives,
 * `flickerPermanents` (exile all, *then* return all) and `exileGraveyard`.
 *
 * The `W8-C` burn-and-removal packet adds three answers (BurnAndRemoval.kt). [dustToDust] is the pool's
 * second card with an **exact** target count above one — "exile two target artifacts", castable only
 * against a board holding two — and it needed no new mechanism at all, `FW-MULTITGT` and the removal
 * packet's exile between them. [cryoshatter] is the card Removal.kt and this file have both listed as
 * absent since the removal packet: it brings the two [dev.mtgplay.core.definition.TriggerCondition]
 * members nothing in the engine watched for — a permanent *becoming tapped* (CR 701.20a) and a permanent
 * *being dealt* damage (CR 120.3d) — plus `AnyOf`, the "When X **or** Y" combinator that keeps one printed
 * ability one declared ability, and a single announcement site reached by all four ways a permanent becomes
 * tapped. [ridesEnd] closes the `FW-TGTCOND` gap `FW-COST` recorded: a spell that prices itself off its own
 * chosen target, which needs the castability gate to price the *cheapest* target choice and the target
 * request to be narrowed to what the seat can pay for. Four of the packet's seven cards stay absent, each
 * on a framework it does not own, and BurnAndRemoval.kt's header carries the full diagnoses: **Searing
 * Blaze** prints two separate — and *dependent* — instances of the word "target"; **Torch the Tower** needs
 * an optional additional *sacrifice* cost, a token-aware sacrifice filter, and above all a **delayed**
 * CR 614 replacement keyed on the permanents it damaged; **Gorilla Shaman** needs `{X}` on an *activated*
 * ability plus the CR 601.2b announcement moved back above the target stage, which
 * `PendingCastRequest.kt`'s header already names it as the card that would force; and **Cleansing
 * Wildfire** needs a library search whose decider is not the resolving spell's controller, whose shuffle is
 * conditional on choosing to search, and which is followed by a further instruction — a *mid*-resolution
 * clause where `FW-CLAUSEHOOK` shipped a post-resolution one.
 *
 * The `FW-TAPUNTAP` packet adds the pool's first cards that **tap, untap, or choose a permanent of
 * their own** (TapEffects.kt): [sleepOfTheDead] (tap target creature, and it does not untap next untap
 * step), [quirionRanger] (return a Forest you control: untap target
 * creature, once each turn), [snap] (bounce a creature, untap up to two lands) and [azoriusChancery]
 * (`{T}: Add {W}{U}`, and an untargeted enters-the-battlefield land bounce). It brings four
 * primitives with them — `tapPermanent`/`untapPermanent` as CR 701.21a/701.21b *effects* rather than
 * cost bookkeeping, [dev.mtgplay.core.definition.AbilityCost.ReturnPermanentYouControl],
 * [dev.mtgplay.core.definition.PermanentSelection] (the untargeted CR 609.4 choice), and
 * [dev.mtgplay.core.definition.ManaAmount.FixedMultiset] (the mixed production `ManaAbility`'s own KDoc
 * had recorded as inexpressible) — and it closed a live defect on the way: `returnPermanentToOwnersHand`
 * fired no CR 603.6c leaves-the-battlefield trigger, so bouncing a [journeyToNowhere] left the creature
 * it held exiled forever. Sewer-veillance Cam and Stonehorn Dignitary stay absent — modal resolution for
 * a *triggered ability* and CR 500.6 phase-skipping are frameworks this packet does not own.
 *
 * The `W8-E` packet adds six cards whose printed work happens as they arrive, or from a zone the engine
 * had never run an ability from (EtbCreatures.kt, Tokens.kt). [faerieMiscreant] carries the pool's first
 * intervening-if whose two CR 603.4 checks can genuinely disagree
 * ([dev.mtgplay.core.definition.InterveningIf.YouControlAnotherCreatureNamed], and with it the pool's
 * first *name* comparison); [godPharaohsFaithful] filters the cast trigger by the cast spell's **colour**
 * (CR 105.2); [gatecreeperVine] brings both new search axes — the "you may search" that is not CR 701.18b's
 * fail-to-find, and the first *disjunctive* filter, "a basic land card **or** a Gate card";
 * [brambleWurm] is the first card with an ability that functions from the **graveyard** (CR 113.6b), paid
 * for by exiling itself; [trollOfKhazadDum] brings the first block restriction that is a property of the
 * whole declaration rather than of a pairing (CR 509.1b, "except by three or more creatures"); and
 * [rallyAtTheHornburg] is the first spell to create two tokens and then pump the set they joined, its
 * [humanSoldierToken] created on demand like every other token here. Three cards were written and
 * dropped. Clockwork Percussionist stays dropped for exactly the reason the keyword-tail packet recorded
 * — a *play permission* lasting "until the end of your next turn" is neither a continuous effect nor a
 * single-turn duration — and the card-advantage packet owns that framework via Reckless Impulse.
 * Moon-Circuit Hacker needs "unless this creature entered this turn", a fact no object records, plus a
 * *conditional* discard hanging off an optional draw; Masked Vandal needs an optional graveyard-exile
 * cost gating a targeted effect mid-resolution, which is a clause
 * [dev.mtgplay.core.definition.ResolutionClauses] does not have.
 *
 * `W8-B` adds four cards about **making mana and spending less of it**, three of which were previously
 * blocked and one of which needed nothing at all. [elvesOfDeepShadow] (ManaCreatures.kt) is the pool's
 * first mana ability with a non-mana **rider** ("This creature deals 1 damage to you"), which stays a
 * mana ability under CR 605.1a and therefore stays stackless and stays in the payment planner;
 * [burningTreeEmissary] (ManaCreatures.kt) is the first triggered ability that **adds mana without
 * being one** (CR 605.1b wants a trigger off a mana ability, and entering the battlefield is not one),
 * so its `{R}{G}` arrives on the stack and floats into the priority window that pays for the next
 * Emissary; [lotusPetal] (CostedManaSources.kt) is trap **T2** finally encodable, its `{T}` *and*
 * sacrifice cost being exactly what `FW-MANACOST`'s composite [dev.mtgplay.core.definition.ManaAbilityCost]
 * list exists to say; and [sunscapeFamiliar] (CostReduction.kt) is the card `FW-COST`'s C6 half was
 * built for and could not ship, needing only `Keyword.DEFENDER` — which `FW-COUNTERS` supplied. The
 * packet adds [dev.mtgplay.core.definition.ManaAbilityRider] and
 * [dev.mtgplay.core.definition.TriggeredAbility.addsMana] in core, carries the rider through the
 * payment-equivalence key so two sources charging different life never collapse (ADR-005), and widens
 * the acceptance module's floating-mana invariant with its second declared exception.
 *
 * Three of its cards stay absent, each on a framework it does not own and each with its cost or mana
 * half already expressible. **Tinder Wall**'s ritual is `ManaAbility(cost = [SacrificeSelf], amount =
 * Fixed(2))` today; its "{R}, Sacrifice this creature: It deals 2 damage to target creature **it's
 * blocking**" needs a targeting restriction stated relative to the ability's *source object*, captured
 * as last-known information at activation because the sacrifice cost has already made that source a new
 * object in a graveyard (CR 400.7, CR 113.7c) — the engine's `Chooser.Ability` carries a
 * [dev.mtgplay.core.identity.CardRef] and deliberately no id. **Tolarian Terror** prints
 * [crypticSerpent]'s graveyard clause exactly and adds **ward {2}** (CR 702.21a), a triggered
 * pay-or-be-countered ability needing a becomes-the-target trigger condition, a parameterised keyword,
 * and the ability to counter an *ability* on the stack (`FW-WARD`). **Deem Inferior**'s "{1} less for
 * each card you've drawn this turn" is expressible — [dev.mtgplay.core.state.PlayerState.drawsThisTurn]
 * has existed since Sneaky Snacker — but its effect puts a permanent "into their library second from the
 * top or on the bottom" **at its owner's choice**, which is a library-position insertion nothing
 * performs plus a non-controller mid-resolution decision.
 *
 * The `W8-A` packet adds the Gates deck's colour-fixing cycle and three utility permanents, and between
 * them they close four absences this file had been recording. Gates.kt brings [citadelGate],
 * [cliffgate] and [manorGate] — "This land enters tapped. As this land enters, choose a color other
 * than &lt;its own&gt;. `{T}`: Add &lt;its own&gt; or one mana of the chosen color" — which widened
 * `CardDefinition.choosesColorAsItEnters` from a flag into
 * [dev.mtgplay.core.definition.AsEntersColorChoice] (a printed line that *excludes* a colour), taught
 * the play-land special action to pause for that CR 614.12 choice at all (a land is never cast, so the
 * flow had only ever run inside a resolving permanent spell), and gave a mana ability an option read off
 * the **object** rather than the card
 * ([dev.mtgplay.core.definition.ManaAbility.includesChosenColor]). NonbasicLands.kt gains
 * [mortuaryMire], whose optional enters-the-battlefield trigger is the first client of
 * [dev.mtgplay.core.definition.TriggeredAbility.optional] — the "you may" that wraps a whole ability
 * and is answered a priority round after its target was chosen — and [conduitPylons], the first client
 * of **surveil** ([dev.mtgplay.core.definition.LibraryLookMode.Surveil], CR 701.44a), the fourth
 * arrangement destination docs/design/library-look.md §12 had listed as a non-goal. BendersWaterskin.kt
 * gains [bendersWaterskin], whose "Untap this artifact during each other player's untap step" is the
 * pool's first CR 613.11 *rules-modifying* static — it changes which permanents the CR 502.2 turn-based
 * action untaps, which no CR 613 layer can express. Bonder's Ornament stays absent, and its recorded
 * reason expired long ago: "add one mana of any color" has been expressible since `FW-MANA`, and
 * [bendersWaterskin] prints exactly that ability.
 *
 * The `W8-D` packet adds six cards across three files and closes four gaps earlier packets had written
 * down by name. **CardAdvantage.kt**: [mulldrifter], the pool's first **evoke** card (CR 702.74 — an
 * alternative cost *plus* a self-sacrifice trigger, so the keyword is two abilities and encoding only the
 * cheap cast would have produced a Mulldrifter that never dies); [windingWay], the card-selection
 * family's last absentee, whose "choose creature or land" is made **as the spell resolves** rather than
 * at CR 601.2b — exactly the diagnosis CardSelection.kt recorded — and whose keep is *all*, not "up to";
 * and [recklessImpulse], whose "until the end of your next turn, you may play those cards" is the first
 * permission in the engine granted **by an effect to another object** rather than declared on the card
 * being played, and so the first that could not be a
 * [dev.mtgplay.core.definition.CastingPermission]. **GraveyardArtifacts.kt**: [nihilSpellbomb] and
 * [relicOfProgenitus], the two cards GraveyardHate.kt left behind when Bojuka Bog landed, each unblocked
 * exactly where that file said it was stuck — an optional *mana* payment inside a resolution, a decision
 * made by a **non-controller**, and an `AbilityCost` member for "Exile this artifact". **DreadReturn.kt**:
 * [dreadReturn], which docs/design/graveyard-targeting.md §6 recorded as blocked on one narrow thing —
 * a flashback cost naming a card type ("Sacrifice three creatures") where
 * [dev.mtgplay.core.definition.SacrificeRequirement] could only name a printed subtype; the fix folds
 * that requirement onto the [dev.mtgplay.core.definition.SacrificeFilter] the cast-side and
 * activation-side sacrifice costs already used, so all three now share one answer.
 *
 * Two of the packet's cards stay absent, diagnosed in full in CardAdvantage.kt: **Fanatical Offering**
 * needs **explore** (CR 701.40) for its Map token, a conditional mid-resolution clause no existing one
 * has; **Monstrous Emergence** needs an additional cost that is a *choice between two shapes*, neither of
 * which consumes what it names, whose two branches read power from the battlefield and from a hand
 * respectively (CR 613 versus CR 109.3).
 *
 * The `FW-PREVENT2` packet adds the gauntlet's **prevention pair** (Flashback.kt): [flaringPain], whose
 * CR 615.9 "damage can't be prevented" is the off-switch for the whole CR 615 framework, and
 * [prismaticStrands], whose CR 615.1 colour shield is the thing it switches off. Between them they are
 * the first cards to put anything in the global prevention store; Prismatic Strands is additionally the
 * first card whose flashback cost is not mana at all — "Tap an untapped white creature you control"
 * (CR 702.34c) — and the first to choose a colour *as it resolves* (CR 609.4) rather than as a permanent
 * enters (CR 614.12, Utopia Sprawl). The same packet adds [troublemakerOuphe] (AdditionalCostCards.kt),
 * whose **bargain** opens the one cost cell the engine lacked — optional *and* object-choosing — and
 * whose enters trigger reads the answer back through a second [InterveningIf] member. That packet's
 * four remaining cards are absent, with their diagnoses recorded in AdditionalCostCards.kt.
 */
object MvpCards {
    /** Every defined card, keyed by its printed-name [CardRef] (CR 201). */
    val definitions: Map<CardRef, CardDefinition> =
        listOf(
            abundantGrowth,
            ancestralMask,
            ancientGrudge,
            ancientStirrings,
            annul,
            archaeomancer,
            augurOfBolas,
            armadilloCloak,
            ashBarrens,
            basiliskGate,
            balustradeSpy,
            azoriusChancery,
            bloodFountain,
            bojukaBog,
            brambleWurm,
            blueElementalBlast,
            bondersOrnament,
            barrelsOfBlastingJelly,
            brainstorm,
            burningTreeEmissary,
            breathWeapon,
            brinebarrowIntruder,
            cartoucheOfSolidarity,
            castIntoTheFire,
            bendersWaterskin,
            castDown,
            citadelGate,
            cliffgate,
            contaminatedLandscape,
            conduitPylons,
            counterspell,
            cropRotation,
            cryoshatter,
            crypticSerpent,
            dispel,
            dreadReturn,
            drossforgeBridge,
            duress,
            elvesOfDeepShadow,
            dustToDust,
            elvishMystic,
            endTheFestivities,
            envelop,
            ephemerate,
            etherealArmor,
            evisceratorsInsight,
            expeditionMap,
            extractAConfession,
            goblinBushwhacker,
            landGrant,
            prohibit,
            faerieMacabre,
            faerieMiscreant,
            faerieSeer,
            faithlessLooting,
            fieryTemper,
            fireblast,
            flaringPain,
            forceSpike,
            forest,
            fyndhornElves,
            galvanicBlast,
            gatecreeperVine,
            generousEnt,
            giantsBoulder,
            gingerbreadCabin,
            gingerbrute,
            glacialFloodplain,
            ghostlyFlicker,
            gladecoverScout,
            gnawToTheBone,
            godPharaohsFaithful,
            goblinTombRaider,
            grabThePrize,
            greatFurnace,
            grizzlyBears,
            harrierStrix,
            ghostlyFlicker,
            gutShot,
            guttersnipe,
            guardianOfTheGuildpact,
            hauntedFengraf,
            healerOfTheGlade,
            highwayRobbery,
            hillGiant,
            ichorWellspring,
            idyllicBeachfront,
            hydroblast,
            impulse,
            island,
            journeyToNowhere,
            kessigFlamebreather,
            krarkClanShaman,
            kruphixsInsight,
            lastBreath,
            lavaDart,
            leadTheStampede,
            lembas,
            lifelink,
            lightningBolt,
            lorienRevealed,
            lotlethGiant,
            lotusPetal,
            makeshiftMunitions,
            manorGate,
            maskOfLawAndGrace,
            malevolentRumble,
            meldedMoxite,
            mentalNote,
            mesmericFiend,
            mistvaultBridge,
            mortuaryMire,
            mountain,
            mulldrifter,
            murmuringMystic,
            myrEnforcer,
            negate,
            nihilSpellbomb,
            ninjaOfTheDeepHours,
            ofOneMind,
            outlawMedic,
            overgrownBattlement,
            perilousLandscape,
            plains,
            ponder,
            pyroblast,
            preordain,
            priestOfTitania,
            prismaticStrands,
            pulseOfMurasa,
            pursueThePast,
            quirionRanger,
            rancor,
            rallyAtTheHornburg,
            raze,
            recklessImpulse,
            reckonersBargain,
            relicOfProgenitus,
            redElementalBlast,
            refurbishedFamiliar,
            removeSoul,
            ridesEnd,
            saruliCaretaker,
            scourFromExistence,
            seaGateOracle,
            seatOfTheSynod,
            sentinelsEyes,
            sewerVeillanceCam,
            silhanaLedgewalker,
            silverbluffBridge,
            rooftopPercher,
            skred,
            slagwoodsBridge,
            sleepOfTheDead,
            slipperyBogle,
            smashToSmithereens,
            snap,
            sneakySnacker,
            snowCoveredIsland,
            snowCoveredMountain,
            snowCoveredPlains,
            spellPierce,
            spellstutterSprite,
            spinewoodsPaladin,
            steelSabotage,
            spiritLink,
            standardBearer,
            standingTroops,
            sunscapeFamiliar,
            stonehornDignitary,
            swamp,
            tamiyosSafekeeping,
            terminate,
            thoughtcast,
            thoughtScour,
            thrabenCharm,
            timberwatchElf,
            toxinAnalysis,
            trollOfKhazadDum,
            troublemakerOuphe,
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
            vituGhaziInspector,
            volatileFjord,
            voldarenEpicure,
            wallOfRoots,
            wellwisher,
            wildGrowth,
            windDrake,
            windingWay,
            youthfulKnight,
        ).associateBy { CardRef(it.characteristics.name) }
}
