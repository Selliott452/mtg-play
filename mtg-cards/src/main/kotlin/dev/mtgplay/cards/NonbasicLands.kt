package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.engine.countMatchingPermanents
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's nonbasic lands whose whole printed text is mana production plus — at most — the
 * CR 614.1c "this land enters tapped" self-replacement: the three Mirrodin artifact lands, the four
 * Modern Horizons Bridges, and Idyllic Beachfront.
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
 * Every mechanism used is a published primitive (ADR-003); the nonbasic lands of the gauntlet that need
 * more than these — a colour chosen as the land enters and then produced, two mana from one activation,
 * a targeted enters-the-battlefield trigger, a search that puts a land onto the battlefield, surveil, a
 * costed mana ability, a conditional enters-tapped clause — are deliberately absent rather than
 * approximated.
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
