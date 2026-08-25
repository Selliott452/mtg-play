package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.PermanentSelection
import dev.mtgplay.core.definition.PermanentSelectionAction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import dev.mtgplay.rules.effect.skipNextUntapStep
import dev.mtgplay.rules.effect.tapPermanent
import dev.mtgplay.rules.effect.untapPermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **tap, untap, and choose-your-own-permanent** cards (`FW-TAPUNTAP`): Mono Blue
 * Faeries' Snap, Elves' and Spy Combo's Quirion Ranger, Mono-Blue Terror's Sleep of the Dead, and UWX
 * Familiar's Azorius Chancery. Harrier Strix was the packet's fifth and is the first client of
 * [dev.mtgplay.rules.effect.tapPermanent], but `FW-NINJUTSU` encoded the same card in parallel, so it
 * lives in Ninjas.kt with the rest of the Faeries tempo shell.
 *
 * Four cards, four new primitives, and the primitives are the point — until this packet the engine
 * could tap and untap only as *bookkeeping* (a `{T}` cost, a mana ability's cost, the CR 502.2 untap
 * step) and had no way for a card to say either. What arrives with them:
 * - [dev.mtgplay.rules.effect.tapPermanent] and [dev.mtgplay.rules.effect.untapPermanent], the CR
 *   701.21a / CR 701.21b resolution effects, which — unlike their cost-side cousins — do nothing to a
 *   permanent already in the requested status rather than failing. They *do* fail on an id that names
 *   no permanent: `FW-NINJUTSU` shipped a second copy of `tapPermanent` with that contract and it is
 *   the one the merge kept, because a bad id there is an ADR-005 defect rather than a rules case.
 * - [AbilityCost.ReturnPermanentYouControl], the third cost component with a *chosen* object, and
 *   [ActivatedAbility.oncePerTurn], the CR 602.5b restriction [ManaAbility] already carried.
 * - [PermanentSelection], the untargeted mid-resolution choice of battlefield permanents that Snap's
 *   "Untap up to two lands" and the Chancery's enters-the-battlefield bounce both need.
 * - [ManaAmount.FixedMultiset], the mixed production the Chancery's "{T}: Add {W}{U}" needs, which
 *   `ManaAbility`'s own KDoc had been recording as inexpressible.
 *
 * **Two cards from the packet's list are deliberately absent**, both for missing frameworks rather
 * than missing primitives, and both are recorded in the packet report:
 * - **Sewer-veillance Cam** — "When this artifact enters **or leaves** the battlefield, you may tap
 *   **or** untap target creature." The two conditions are Ichor Wellspring's (two entries, disjoint
 *   events) and both branches are this file's primitives, but the resolution is a *mode* choice on a
 *   triggered ability — decline, tap, or untap — and modal resolution exists only for spells
 *   ([SpellDefinition.modes] with `ModalSpell`, `FW-MODAL`). Encoding it as tap-only or untap-only
 *   would be a plausible-looking wrong card (PLAN.md §7).
 * - **Stonehorn Dignitary** — "target opponent skips their next combat phase". A CR 500.6 skip effect
 *   is a *delayed* per-player fact, and `positionAfter` decides skipping from the [Turn] alone with no
 *   access to player state, so the card needs a skip framework rather than a primitive.
 */

/** Sleep of the Dead, for the target it taps (CR 115.1b). */
private val SLEEP_OF_THE_DEAD: CardRef = CardRef("Sleep of the Dead")

/** Snap, for the creature it returns (CR 115.1b). */
private val SNAP: CardRef = CardRef("Snap")

/** Quirion Ranger, for the creature its ability untaps (CR 115.1b). */
private val QUIRION_RANGER: CardRef = CardRef("Quirion Ranger")

/** How many *other* graveyard cards Sleep of the Dead's escape exiles (CR 702.139a). */
const val SLEEP_OF_THE_DEAD_ESCAPE_EXILE: Int = 3

/** How many lands Snap may untap on resolution (CR 609.4). */
const val SNAP_UNTAP_LANDS: Int = 2

/** The land type Quirion Ranger's cost returns (CR 205.3). */
private val FOREST: Subtype = Subtype("Forest")

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield.
 */
private val entersTheBattlefieldOnly: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Sleep of the Dead — `{U}` Sorcery. "Tap target creature. It doesn't untap during its controller's
 * next untap step. Escape—`{2}{U}`, Exile three other cards from your graveyard."
 *
 * Mono-Blue Terror's answer to a creature it cannot kill, and the only card in the pool that writes to
 * [dev.mtgplay.core.state.GameObject.skipsNextUntapStep]. Its two sentences are **two independent
 * instructions**, composed as two calls rather than one flagged primitive, and the composition is what
 * makes the corner case right: a creature that is *already tapped* takes no tap (CR 701.21a) and still
 * receives the marker, so tapping it again in response achieves nothing and Sleeping an already-tapped
 * attacker still holds it down for a turn.
 *
 * **"Its controller's next untap step"**, not "your next" and not "the next": the marker is spent by the
 * marked permanent's own controller's untap step (CR 502.2), which is where the engine consumes it.
 * That is one turn of tapping down on the opponent's own creature and — cast on your own turn against
 * an untapped blocker — two turns of it in game terms.
 *
 * Escape (CR 702.139) is [CastingPermission.Escape] and needed no new machinery: `{2}{U}` plus exiling
 * three *other* graveyard cards, enumerated only when the graveyard holds three and the mana is
 * affordable (ADR-005). Three, not two — Sentinel's Eyes' is two, and the counts are per card.
 */
val sleepOfTheDead: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = SLEEP_OF_THE_DEAD.name,
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                val creature = onePermanentTarget(context.targets, SLEEP_OF_THE_DEAD.name)
                // CR 701.21a then CR 502.2: two instructions, and the second applies whether or not the
                // first did anything (an already-tapped creature is untouched by the tap, marked by this).
                skipNextUntapStep(tapPermanent(state, creature), creature)
            }
        override val castingPermissions =
            listOf(
                CastingPermission.Escape(
                    cost = ManaCost.parse("{2}{U}"),
                    exileOthers = SLEEP_OF_THE_DEAD_ESCAPE_EXILE,
                ),
            )
    }

/**
 * Snap — `{1}{U}` Instant. "Return target creature to its owner's hand. Untap up to two lands."
 *
 * A free-in-practice bounce: the two untapped lands pay for it retroactively, which is the whole
 * reason UWX Familiar and Faeries play it. Two halves, and they are **different kinds of choice** —
 * the creature is a target (CR 115.1b, chosen at CR 601.2c and re-checked at CR 608.2b), the lands are
 * not.
 *
 * **"Untap up to two lands" names no controller**, and the engine offers exactly that: any land on the
 * battlefield, an opponent's included. That is the printed card — the Oracle text says "two lands", not
 * "two lands you control" — and it matters in play, because untapping an opponent's land is
 * occasionally the right line and is always a legal one. The gauntlet triage recorded this half as
 * "choose-up-to-N-permanents-**you-control**", which is wrong; the filter here carries no controller
 * restriction.
 *
 * **Hexproof does not narrow the land options.** They are chosen on resolution (CR 609.4), not targeted,
 * so CR 702.11a never applies to them — while the creature half *is* targeted and a hexproof creature
 * an opponent controls is not offered. One card, both readings, and the split is visible in the two
 * declarations below.
 *
 * The spell **fizzles entirely if its creature target is gone** (CR 608.2b) and no land is untapped:
 * the untap is part of the same resolution, and a spell that does not resolve performs none of its
 * instructions. That is the rules answer and it is why the clause hangs off this definition rather
 * than being a second, independent effect.
 */
val snap: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = SNAP.name,
                manaCost = ManaCost.parse("{1}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                returnPermanentToOwnersHand(state, onePermanentTarget(context.targets, SNAP.name))
            }

        // CR 609.4: chosen on resolution, over *every* land on the battlefield — no controller axis.
        override val permanentSelection =
            PermanentSelection(
                filter = PermanentFilter(controlledByYou = false, cardType = CardType.LAND),
                minimum = 0,
                maximum = SNAP_UNTAP_LANDS,
                action = PermanentSelectionAction.UNTAP,
            )
    }

/**
 * Quirion Ranger — `{G}` Creature — Elf Ranger 1/1. "Return a Forest you control to its owner's hand:
 * Untap target creature. Activate only once each turn."
 *
 * The Elves and Spy Combo engine piece, and the card the whole [AbilityCost.ReturnPermanentYouControl]
 * component exists for. Its cost is **the return and nothing else** — no mana, no `{T}` — which makes
 * it the first ability in the pool whose entire cost is a chosen object, and the reason
 * [ActivatedAbility.oncePerTurn] had to arrive with it: without the CR 602.5b restriction the ability
 * would be activatable as many times as the player has Forests to bounce and replay.
 *
 * **The Ranger does not tap itself, so the untapped creature may be the Ranger.** Combined with a
 * Forest replayed for the turn's land drop, that is the printed engine: return the Forest, untap a
 * mana Elf, replay the Forest. Nothing here special-cases it.
 *
 * **"Untap target creature" carries no controller restriction**, so the spec is plain
 * [PermanentRestriction.CREATURE] and an opponent's creature is a legal target too — untapping a
 * potential blocker is a real, if rare, line. Narrowing it to `CREATURE_YOU_CONTROL` because the
 * card is only ever *used* on your own board would be the engine inventing a restriction the card
 * does not print.
 *
 * **"A Forest you control" is a land *type*, not a card name** (CR 205.3), which is why the cost
 * carries a [PermanentFilter] rather than the card-type-only `SacrificeFilter`: a Bayou or a Yavimaya
 * Coast would qualify, and in this gauntlet a plain Forest is what turns up. `controlledByYou = true`
 * is the printed "you control".
 *
 * **Summoning sickness does not stop it** (CR 302.6): the restriction reaches only `{T}` and `{Q}`
 * costs, and this ability has neither — a Ranger played this turn can untap a creature immediately.
 * The engine's `abilityCostPayable` checks the sickness gate on [AbilityCost.TapSelf] alone, so this
 * comes out right with no card-side note.
 */
val quirionRanger: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = QUIRION_RANGER.name,
                manaCost = ManaCost.parse("{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Elf"), Subtype("Ranger")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefieldOnly
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.ReturnPermanentYouControl(
                                PermanentFilter(subtype = FOREST, controlledByYou = true),
                            ),
                        ),
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    effect =
                        ResolutionEffect { state, context ->
                            untapPermanent(state, onePermanentTarget(context.targets, QUIRION_RANGER.name))
                        },
                    // CR 602.5b: "Activate only once each turn", recorded per object.
                    oncePerTurn = true,
                ),
            )
    }

/**
 * Azorius Chancery — Land. "This land enters tapped. When this land enters, return a land you control
 * to its owner's hand. {T}: Add {W}{U}."
 *
 * A Karoo (CR 305): it costs a turn and a land to install and then pays two mana every turn after,
 * which is why UWX Familiar plays it. Three printed halves, and two of them were the packet's headline
 * gaps:
 *
 * **"{T}: Add {W}{U}" is [ManaAmount.FixedMultiset]** — one activation adding two mana of *different*
 * types, with no choice between them. Until this packet [ManaAbility] could say "add N mana of one of
 * these types" and nothing else, and its own KDoc recorded the Chancery as the card that would need
 * another `ManaAmount`. It is not the same card as "add one mana of white or blue": the Chancery's
 * activation puts a `{W}` *and* a `{U}` in the pool, and encoding it as a choice would halve it.
 *
 * **The enters-the-battlefield bounce is untargeted** ([PermanentSelection]): the printed line is
 * "return **a** land you control", with no "target", so the land is chosen as the trigger resolves
 * (CR 609.4). The consequences are real — nobody may respond to the choice, and the trigger cannot
 * fizzle for want of a legal one.
 *
 * **The Chancery is a legal choice for its own trigger, and that is the card.** With no other land to
 * spare, bouncing itself is the standard (miserable) play; excluding the source would silently delete
 * it. `minimum = 1` makes the return mandatory, clamped by the engine to what the board offers, so a
 * controller whose every land was destroyed in response returns nothing rather than being asked for the
 * impossible.
 *
 * **It enters tapped** ([EntersTapped.Always], CR 614.1c), which is the first half of the Karoo's cost
 * and a self-replacement rather than an ability — so it cannot be responded to and generates no
 * trigger of its own.
 */
val azoriusChancery: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Azorius Chancery",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val entersTapped = EntersTapped.Always
        override val manaAbilities =
            persistentListOf(
                ManaAbility(
                    // CR 605.1a: the distinct types the mixed production adds, in WUBRG order.
                    options = persistentListOf(ManaType.WHITE, ManaType.BLUE),
                    amount = ManaAmount.FixedMultiset(persistentListOf(ManaType.WHITE, ManaType.BLUE)),
                ),
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, _ -> state },
                    // CR 609.4: "a land you control" — untargeted, mandatory, and the Chancery qualifies.
                    permanentSelection =
                        PermanentSelection(
                            filter = PermanentFilter(controlledByYou = true, cardType = CardType.LAND),
                            minimum = 1,
                            maximum = 1,
                            action = PermanentSelectionAction.RETURN_TO_OWNERS_HAND,
                        ),
                ),
            )
    }

/**
 * The single permanent [targets] names (CR 115.1b). Fails loudly on anything else: the CR 608.2b
 * re-check has already run, so a resolving object whose spec is a one-target
 * [TargetSpec.TargetPermanent] always holds exactly one legal permanent target (ADR-005), and anything
 * else is an engine defect rather than a rules case.
 */
private fun onePermanentTarget(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $cardName targets exactly one permanent, got $targets")
