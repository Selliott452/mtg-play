package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.putGraveyardCardOnTopOfOwnersLibrary
import dev.mtgplay.rules.engine.countMatchingPermanents
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's nonbasic lands whose whole printed text is mana production plus — at most — the
 * CR 614.1c "this land enters tapped" self-replacement: the three Mirrodin artifact lands, the four
 * Modern Horizons Bridges, and Idyllic Beachfront. Basilisk Gate joined them with a `+X/+X` ability, and
 * `W8-A` added the two utility lands at the foot of the file, [mortuaryMire] and [conduitPylons], whose
 * enters-the-battlefield triggers are the first things here that are neither mana nor a static.
 *
 * Like the basics (BasicLands.kt) each is *played*, not cast (CR 305.1, CR 116.2a), so every one is a
 * plain [CardDefinition] and never a [dev.mtgplay.core.definition.SpellDefinition].
 *
 * **Intrinsic abilities from land types are still authored explicitly** (the P2.2 architect decision
 * recorded in BasicLands.kt): Idyllic Beachfront's type line "Land — Plains Island" grants it
 * "{T}: Add {W}" and "{T}: Add {U}" by CR 305.6, and those are written out here as two abilities rather
 * than derived, exactly as Mountain's `{T}: Add {R}` is. The Bridges' and artifact lands' abilities are
 * printed rules text, not type-derived, so no such question arises for them.
 *
 * Every mechanism used is a published primitive (ADR-003). This paragraph used to list what the rest of
 * the gauntlet's nonbasic lands needed — "a colour chosen as the land enters and then produced, two mana
 * from one activation, a targeted enters-the-battlefield trigger, a search that puts a land onto the
 * battlefield, surveil, a costed mana ability, a conditional enters-tapped clause". Every one of those has
 * since landed: the colour choice and its production in Gates.kt (`W8-A`), surveil on [conduitPylons]
 * below, the targeted enters-the-battlefield trigger on [mortuaryMire], and the rest across
 * `FW-TAPUNTAP`, `P-SEARCH`, `FW-MANACOST` and `P-ETBTAPPED`.
 */

/**
 * Great Furnace — Artifact Land. "{T}: Add {R}." An artifact *and* a land (CR 301, CR 305): it is
 * played as a land (CR 305.1) and is an artifact on the battlefield, which is why the affinity decks
 * count it. One intrinsic mana ability (CR 605.1a).
 */
val greatFurnace: CardDefinition = artifactLand(name = "Great Furnace", produces = ManaType.RED)

/**
 * Seat of the Synod — Artifact Land. "{T}: Add {U}." [greatFurnace]'s blue counterpart.
 */
val seatOfTheSynod: CardDefinition = artifactLand(name = "Seat of the Synod", produces = ManaType.BLUE)

/**
 * Vault of Whispers — Artifact Land. "{T}: Add {B}." [greatFurnace]'s black counterpart.
 */
val vaultOfWhispers: CardDefinition = artifactLand(name = "Vault of Whispers", produces = ManaType.BLACK)

/**
 * Drossforge Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {B} or {R}."
 * The black-red Bridge: a dual-producing artifact land whose cost for the fixing is entering tapped
 * (CR 614.1c, [CardDefinition.entersTapped]). Its printed indestructible (CR 702.12) is a
 * characteristic here; the engine honours it wherever it destroys — the CR 704.5g lethal-damage
 * state-based action, which a land never reaches, and the CR 701.7a destroy effect, which is what
 * makes the keyword matter: an opposing [ancientGrudge] or [smashToSmithereens] targets a Bridge
 * legally and destroys nothing (CR 702.12b).
 */
val drossforgeBridge: CardDefinition = bridge(name = "Drossforge Bridge", ManaType.BLACK, ManaType.RED)

/**
 * Mistvault Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {U} or {B}."
 * The blue-black [drossforgeBridge].
 */
val mistvaultBridge: CardDefinition = bridge(name = "Mistvault Bridge", ManaType.BLUE, ManaType.BLACK)

/**
 * Silverbluff Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {U} or {R}."
 * The blue-red [drossforgeBridge].
 */
val silverbluffBridge: CardDefinition = bridge(name = "Silverbluff Bridge", ManaType.BLUE, ManaType.RED)

/**
 * Slagwoods Bridge — Artifact Land. "This land enters tapped. Indestructible. {T}: Add {R} or {G}."
 * The red-green [drossforgeBridge].
 */
val slagwoodsBridge: CardDefinition = bridge(name = "Slagwoods Bridge", ManaType.RED, ManaType.GREEN)

/**
 * Idyllic Beachfront — Land — Plains Island. "({T}: Add {W} or {U}.) This land enters tapped."
 *
 * The whole card is its type line plus one clause. It has no *printed* mana ability at all — the
 * parenthesised text is reminder text for what CR 305.6 gives any permanent with the Plains and Island
 * land types: the two separate intrinsic abilities "{T}: Add {W}" and "{T}: Add {U}". They are written
 * out as two [ManaAbility] entries, which is the faithful shape (two abilities, not one ability with a
 * choice) and behaves identically, both costing `{T}` on a permanent that can only be tapped once.
 *
 * It has neither the Basic supertype nor any printed keyword; the only rules text is the CR 614.1c
 * "this land enters tapped" ([CardDefinition.entersTapped]).
 */
val idyllicBeachfront: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Idyllic Beachfront",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Plains"), Subtype("Island")),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(
                ManaAbility(persistentListOf(ManaType.WHITE)),
                ManaAbility(persistentListOf(ManaType.BLUE)),
            )
        override val entersTapped = EntersTapped.Always
    }

/**
 * An artifact land (CR 301, CR 305): both card types, no supertype and no land subtype, and one
 * printed intrinsic mana ability adding [produces] (CR 605.1a).
 */
private fun artifactLand(
    name: String,
    produces: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(produces)))
    }

/**
 * A Bridge artifact land: an indestructible artifact land that enters tapped and has the single
 * printed mana ability "{T}: Add [first] or [second]" — one ability offering a choice, so its two
 * options ride in one [ManaAbility]. The options are listed in the WUBRG-then-colorless order the
 * payment enumerator treats as canonical (docs/design/mana-payment.md).
 */
private fun bridge(
    name: String,
    first: ManaType,
    second: ManaType,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
                keywords = persistentSetOf(Keyword.INDESTRUCTIBLE),
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(first, second)))
        override val entersTapped = EntersTapped.Always
    }

/** The land type Basilisk Gate carries and its own ability counts (CR 205.3i). */
private val GATE: Subtype = Subtype("Gate")

private val BASILISK_GATE: CardRef = CardRef("Basilisk Gate")

/**
 * The battlefield permanents Basilisk Gate counts: the Gates **you control**, not every Gate on the
 * battlefield (CR 109.5). The mirror of Timberwatch Elf's `controlledByYou = false`, and in a Gates
 * mirror match the difference is the whole card.
 */
private val GATES_YOU_CONTROL: PermanentFilter = PermanentFilter(subtype = GATE, controlledByYou = true)

/**
 * Basilisk Gate — Land — Gate. "`{T}`: Add `{C}`. `{2}`, `{T}`: Target creature gets +X/+X until end of
 * turn, where X is the number of Gates you control. **Activate only as a sorcery.**"
 *
 * The Gates deck's win condition, and the card `FW-DURATION` wrote its snapshot ruling *for* and then had
 * to drop. Both of the blockers docs/design/duration.md §9.1 named have since landed, and neither needed
 * anything from this definition:
 *
 * - **"Activate only as a sorcery"** is [ActivatedAbility.timing] = [TimingClass.SORCERY_SPEED]
 *   (CR 602.5d), added by `FW-MANACOST` — whose KDoc names this card. Without the field the Gate would
 *   encode as an instant-speed combat trick, which is an enumerated-but-illegal action (ADR-005) rather
 *   than a cosmetic inaccuracy. The window is the *same* predicate a sorcery's cast is checked against,
 *   so the two cannot drift.
 * - **Trap T17** — the Gate is both a mana source and the source of a `{T}`-costed ability with a mana
 *   component, so `enumeratePaymentPlans` used to offer a plan tapping the Gate for `{C}` toward its own
 *   `{2}` and then throw. `FW-MANA`'s `manaSourcesReservedBy` reservation
 *   (docs/design/mana-payment.md §2.2) removes the Gate from the plans offered for its own ability, which
 *   is the fix the note asked for and put where it asked for it.
 *
 * **The magnitude is snapshotted, and X counts the Gate itself.** [countMatchingPermanents] runs inside
 * the resolution, so CR 608.2h/611.2d are satisfied by *where* the count happens rather than by any flag:
 * [applyUntilEndOfTurn] takes plain `Int`s and has nowhere to put a state-reading function, which is
 * exactly what keeps this apart from Ethereal Armor's live-read `Magnitude.Dynamic` (CR 613.3c). A Gate
 * that enters after the ability resolved does **not** grow the pump. And because the Gate must be
 * untapped to pay its own `{T}`, it is always on the battlefield and always counts itself, so X is never
 * zero and the effect is never the empty one [applyUntilEndOfTurn] refuses.
 *
 * **"Target creature"** is [PermanentRestriction.CREATURE] with no control clause, so the Gate can pump
 * an opponent's creature; hexproof (CR 702.11) is the only thing that narrows the enumeration.
 *
 * A land is *played*, never cast (CR 305.1, CR 116.2a), so this is a plain [CardDefinition] like every
 * other land here — the first one whose printed text is more than mana production.
 */
val basiliskGate: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = BASILISK_GATE.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(GATE),
                powerToughness = null,
            )

        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.TapSelf,
                        ),
                    // CR 602.5d: "Activate only as a sorcery" — the sorcery-cast window, not a cost.
                    timing = TimingClass.SORCERY_SPEED,
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 608.2h: X is calculated now, once, and the effect keeps that value.
                            val gates = countMatchingPermanents(state, GATES_YOU_CONTROL, context.controller)
                            applyUntilEndOfTurn(
                                state = state,
                                affected = pumpedCreature(context.targets),
                                modification = ContinuousModification(powerMod = gates, toughnessMod = gates),
                                sourceCard = BASILISK_GATE,
                                source = context.source,
                            )
                        },
                ),
            )
    }

/**
 * The creature Basilisk Gate's ability was told to pump (CR 115.1b), failing loudly on any other target
 * kind or arity: the CR 608.2b re-check has already run (ADR-005).
 */
private fun pumpedCreature(targets: List<Target>): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: ${BASILISK_GATE.name}'s ability targets exactly one permanent, got $targets")

private val MORTUARY_MIRE: CardRef = CardRef("Mortuary Mire")

/** The land type Conduit Pylons carries (CR 205.3i); no ability in the pool counts it. */
private val DESERT: Subtype = Subtype("Desert")

/** What Conduit Pylons' filtering mana ability costs in generic mana (CR 602.1). */
private const val CONDUIT_PYLONS_FILTER_COST: String = "{1}"

/** How many cards Conduit Pylons' enters-the-battlefield trigger surveils (CR 701.44a). */
private const val CONDUIT_PYLONS_SURVEIL: Int = 1

/** The five colours an "add one mana of any color" ability offers, in WUBRG order (CR 105.1). */
private val ANY_COLOR =
    persistentListOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)

/**
 * Mortuary Mire — Land. "This land enters tapped. When this land enters, you may put target creature card
 * from your graveyard on top of your library. `{T}`: Add `{B}`."
 *
 * **The card `FW-ZONETGT` dropped, and the diagnosis it wrote has expired.** GraveyardTargets.kt recorded
 * it as blocked because "`executePlayLand` does not call `detectEnterBattlefieldTriggers` (triage T18)";
 * that path now fires a played land's triggers through the same `announceBattlefieldEntry` a resolving
 * permanent uses, and [bojukaBog] is the card that proved it. What was left is the **"you may"**, and that
 * is this packet's [TriggeredAbility.optional] — the whole of the ability's effect sits inside it.
 *
 * **Two decisions, a priority round apart, and neither collapses into the other.** The target is chosen as
 * the trigger goes on the stack (CR 603.3d) and the "may" is answered when it resolves (CR 608.2c). That
 * gap is the card: an opponent who exiles the graveyard in response makes the target illegal and the
 * trigger fizzles before the question is ever asked, while a controller who drew well in the meantime
 * declines. Encoding the pair as "up to one target" would settle both at CR 603.3d and delete the second.
 *
 * **Declining is a real play**, which is why the "may" is printed and why the engine must offer it:
 * accepting *replaces* the controller's next draw with a card they already know about, so a graveyard
 * holding nothing better than an average draw is a graveyard to leave alone.
 *
 * The target is a **creature card** in the controller's **own** graveyard
 * ([GraveyardCardRestriction.CREATURE], [GraveyardScope.YOURS]) — the narrower of the two creature-ish
 * restrictions, so a land card is never offered. With no creature card there the trigger still goes on the
 * stack with no targets and then does nothing (CR 603.3d, CR 608.2b), which is the common case early.
 */
val mortuaryMire: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = MORTUARY_MIRE.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val entersTapped = EntersTapped.Always

        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 603.2: the printed "you may" wraps the whole instruction, so it gates the effect.
                    optional = true,
                    targetSpec =
                        TargetSpec.CardInGraveyard(
                            restriction = GraveyardCardRestriction.CREATURE,
                            scope = GraveyardScope.YOURS,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            putGraveyardCardOnTopOfOwnersLibrary(state, recoveredCreature(context.targets))
                        },
                ),
            )
    }

/**
 * Conduit Pylons — Land — Desert. "When this land enters, surveil 1. `{T}`: Add `{C}`. `{1}`, `{T}`: Add
 * one mana of any color."
 *
 * **The card docs/design/mana-payment.md §11.7 filed as blocked on exactly one thing**, and this packet is
 * that thing: surveil (CR 701.44a), the destination docs/design/library-look.md §12 listed as a documented
 * non-goal and predicted would be "this hierarchy plus a fourth destination in the arrangement". It is
 * [LibraryLookMode.Surveil], carried on the enters-the-battlefield trigger through the `FW-CLAUSEHOOK`
 * carrier — the same [LibraryLook] clause Preordain declares, on an ability instead of a spell.
 *
 * **Surveil 1 is a two-option decision and both options are real**: the looked-at card goes to the
 * graveyard, where the gauntlet's graveyard decks can use it, or stays on top to be drawn. That is not
 * scry — a scryed card put on the bottom is still in the library — which is why surveil is its own mode
 * rather than a destination flag on [LibraryLookMode.Scry].
 *
 * **Two mana abilities on one source, and the second costs mana** — the "two costs, one source" shape
 * docs/design/mana-payment.md §11.1 was built around, and the card it was built around. The free
 * `{T}`: Add `{C}` sorts ahead of the `{1}`, `{T}` filter in the production profile, so the cheap line sits
 * at the low plan indices; a Pylons cannot fund its own `{1}`, because the filter's `{T}` component
 * reserves the source (trap T17); and two Pylons cannot fund each other, because the enumerator derives an
 * execution order and finds none.
 *
 * It is a **Desert** (CR 205.3i) and the pool's first; nothing in the gauntlet counts Deserts, so the
 * subtype is printed characteristics and nothing more.
 */
val conduitPylons: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Conduit Pylons",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(DESERT),
                powerToughness = null,
            )

        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(
                ManaAbility(persistentListOf(ManaType.COLORLESS)),
                ManaAbility(
                    options = ANY_COLOR,
                    // CR 602.1: printed order — the {1}, then the tap.
                    cost =
                        persistentListOf(
                            ManaAbilityCost.Mana(ManaCost.parse(CONDUIT_PYLONS_FILTER_COST)),
                            ManaAbilityCost.TapSelf,
                        ),
                ),
            )

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 608.2c: the surveil is the clause; the ability has no other instruction.
                    effect = ResolutionEffect { state, _ -> state },
                    libraryLook = LibraryLook(LibraryLookMode.Surveil(CONDUIT_PYLONS_SURVEIL)),
                ),
            )
    }

/**
 * The graveyard card Mortuary Mire's trigger was told to recover (CR 115.1b), failing loudly on any other
 * target kind or arity: the CR 608.2b re-check has already run (ADR-005).
 */
private fun recoveredCreature(targets: List<Target>): ObjectId =
    (targets.singleOrNull() as? Target.CardInGraveyard)?.id
        ?: error("CR 115.1b: ${MORTUARY_MIRE.name}'s ability targets exactly one graveyard card, got $targets")
