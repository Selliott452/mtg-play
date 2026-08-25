package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.counterSpell
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import dev.mtgplay.rules.effect.targetIsColor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's modal instants (`FW-MODAL`, docs/design/countering-spells.md §8): the four colour
 * hosers and Steel Sabotage. Every one is a "Choose one —" card (CR 700.2) whose modes are chosen as
 * the spell is put onto the stack (CR 601.2b), before its targets (CR 601.2c).
 *
 * **The four Blasts are two templates, not one, and this file is where that shows.** The upstream brief
 * grouped them as "modal — counter or destroy — and colour-conditional"; the oracle text splits them
 * cleanly, and docs/design/countering-spells.md §1.2 called it. The split is a difference in *where the
 * colour test lives*, and it changes what the engine enumerates:
 *
 * | | Blue/Red Elemental Blast | Hydroblast/Pyroblast |
 * |---|---|---|
 * | Oracle | "Counter target **red** spell" | "Counter target spell **if it's red**" |
 * | Colour test | in the **targeting line** | in the **effect** |
 * | Encoded as | [SpellRestriction.OfColor] / `RED_PERMANENT` | [targetIsColor] inside the resolution |
 * | Ineligible object | not a legal target — mode absent from enumeration | a legal target — mode offered |
 * | Nothing eligible on board | **uncastable**, absent from the priority window | **castable**, and correctly so |
 * | Target stops qualifying | fizzles (CR 608.2b) | resolves and does nothing (CR 608.2c) |
 *
 * Encoding the right column as the left is a **gap in enumeration completeness** — the agent is never
 * offered a legal play — and is the single failure ADR-005's tested property exists to catch. Encoding
 * the left as the right is the milder mirror: an illegal target offered, caught at CR 601.2c. Both are
 * wrong; the first is wrong *silently*, which is why the two shapes are kept apart by construction here
 * (a restriction is data the engine filters on; a condition is code inside a resolution) rather than by a
 * comment asking somebody to be careful.
 *
 * Oracle text below is Scryfall's, fetched for this packet (`POST /cards/collection`, all seven found);
 * where it disagreed with the design note or the upstream brief, the oracle text won.
 */

/**
 * Blue Elemental Blast — `{U}` Instant. "Choose one — • Counter target red spell. • Destroy target red
 * permanent."
 *
 * The **target-restricted** template. Both modes name a red object in their targeting line, so
 * [SpellRestriction.OfColor]`(RED)` and [PermanentRestriction.RED_PERMANENT] carry the whole of the
 * colour test and the engine never needs to know what "red" means at resolution time.
 *
 * Three consequences, each one a test:
 * - With **no red object anywhere** — none on the stack, none on the battlefield — neither mode has a
 *   legal target, so the card is **absent from the priority window entirely** (ADR-005). That is the
 *   correct answer and the opposite of Pyroblast's.
 * - With a red permanent but **no red spell**, exactly **one** mode is offered. Mode availability is
 *   per-mode, not all-or-nothing.
 * - A target that **stops being red** — or simply leaves its zone — fizzles the Blast at the CR 608.2b
 *   re-check. Nothing in the pool changes an object's colour, so today the reachable route is the object
 *   leaving.
 *
 * Its destroy mode is the pool's first "destroy target *permanent*" that is not creature- or
 * artifact-shaped: a red enchantment or a red land would be a legal choice. No land is, in fact, ever
 * legal — a land has no mana cost and so is colourless (CR 105.2) — which [PermanentRestriction.RED_PERMANENT]
 * records because "destroy target red permanent" reads as though a Mountain ought to qualify.
 */
val blueElementalBlast: SpellDefinition =
    restrictedBlast(name = "Blue Elemental Blast", cost = "{U}", hosed = Color.RED)

/**
 * Red Elemental Blast — `{R}` Instant. "Choose one — • Counter target blue spell. • Destroy target blue
 * permanent."
 *
 * [blueElementalBlast]'s mirror, and the pool's only **red** counter — the card that proves the counter
 * framework is not a blue-only mechanism, since `SpellRestriction` lives on the targeting line rather
 * than on any colour of card. Everything [blueElementalBlast] documents applies with the colours
 * exchanged.
 */
val redElementalBlast: SpellDefinition =
    restrictedBlast(name = "Red Elemental Blast", cost = "{R}", hosed = Color.BLUE)

/**
 * Hydroblast — `{U}` Instant. "Choose one — • Counter target spell if it's red. • Destroy target
 * permanent if it's red."
 *
 * The **effect-conditional** template, and [blueElementalBlast]'s deliberate opposite. Its targeting
 * lines are *unrestricted* — [SpellRestriction.Any] and [PermanentRestriction.ANY_PERMANENT] — and the
 * colour test sits inside each mode's resolution as [targetIsColor]. So it may legally target a white
 * spell or a Forest, resolve, and do nothing (CR 608.2c).
 *
 * That is not a modelling convenience; it is the card. An implementation that copied
 * [blueElementalBlast]'s shape onto this one would remove a legal cast from the agent's option list —
 * ADR-005's completeness failure, and invisible unless a test looks for it. Against a real opponent the
 * "wasted" cast is sometimes even right: it bluffs, and it bins a dead card.
 *
 * It also fizzles for a *different reason* than its sibling. Kill Hydroblast's target and it fizzles
 * (CR 608.2b — the target is gone). Leave the target alive but non-red and it does **not** fizzle: it
 * resolves, tests the colour, and declines to do anything. Only the second case distinguishes the two
 * templates, and only this card can show it.
 */
val hydroblast: SpellDefinition =
    conditionalBlast(name = "Hydroblast", cost = "{U}", hosed = Color.RED)

/**
 * Pyroblast — `{R}` Instant. "Choose one — • Counter target spell if it's blue. • Destroy target
 * permanent if it's blue."
 *
 * [hydroblast]'s mirror and the enumeration-completeness card of the whole packet: **Pyroblast must be
 * enumerable against a white spell**, because "counter target spell if it's blue" restricts the effect
 * and not the target. Getting that backwards is the silent ADR-005 defect
 * docs/design/countering-spells.md §1.2 warns about, and it is pinned by a test that casts it at a white
 * spell and watches it resolve into nothing.
 */
val pyroblast: SpellDefinition =
    conditionalBlast(name = "Pyroblast", cost = "{R}", hosed = Color.BLUE)

/**
 * Steel Sabotage — `{U}` Instant. "Choose one — • Counter target artifact spell. • Return target artifact
 * to its owner's hand."
 *
 * The card that makes the CR 601.2b-before-CR 601.2c ordering **unavoidable** rather than merely correct.
 * Its two modes target different *kinds* of object — a spell on the stack ([TargetSpec.SpellOnStack]) and
 * a battlefield permanent ([TargetSpec.TargetPermanent]) — and they are not even the same *sort* of
 * artifact: mode 0 wants an artifact spell that has not resolved, mode 1 wants an artifact that has. So
 * "what are the legal targets of Steel Sabotage?" is a question with no answer until the mode is known,
 * and an engine that enumerated targets first would have nothing to enumerate against.
 *
 * The Blasts share that property, so it is the modal *framework* this pins rather than anything unique to
 * this card. What is unique is the pairing: it is the pool's only modal card whose two modes do genuinely
 * different things (counter versus bounce) rather than the same thing to two kinds of object, so it is the
 * one whose mode choice is a real strategic decision rather than a consequence of what is on the board.
 *
 * **The design note was wrong about this card's second mode.** §8 says it "needs nothing new:
 * `returnToOwnersHand` already exists" — but that primitive returns a **graveyard** object (it is
 * Rancor's leaves-the-battlefield trigger's helper, and it searches only graveyards). Bouncing a
 * battlefield artifact is a different zone change with different consequences — combat release
 * (CR 506.4), Aura fall-off at the next CR 704.5m check — so this packet added
 * [returnPermanentToOwnersHand] beside it rather than widening the graveyard one.
 */
val steelSabotage: SpellDefinition =
    object : ModalSpell {
        override val characteristics = blastCharacteristics("Steel Sabotage", "{U}", CardType.INSTANT)
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target artifact spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.OfCardType(CardType.ARTIFACT)),
                    resolution =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
                SpellMode(
                    text = "Return target artifact to its owner's hand.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT),
                    resolution =
                        ResolutionEffect { state, context ->
                            returnPermanentToOwnersHand(state, targetedPermanent(context.targets, "Steel Sabotage"))
                        },
                ),
            )
    }

/**
 * A **target-restricted** Blast (CR 115.1): "Counter target &lt;colour&gt; spell" / "Destroy target
 * &lt;colour&gt; permanent". [hosed] is the colour the card answers, and it appears only in the two
 * targeting lines — the resolutions are the bare [counterSpell] and [destroy] primitives, which never
 * learn what colour anything is.
 *
 * That the effects are colour-blind *is* the template: the engine filters the option list, so an
 * ineligible object is unreachable rather than rejected.
 */
private fun restrictedBlast(
    name: String,
    cost: String,
    hosed: Color,
): SpellDefinition =
    object : ModalSpell {
        override val characteristics = blastCharacteristics(name, cost, CardType.INSTANT)
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target ${hosed.blastWord} spell.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.OfColor(hosed)),
                    resolution =
                        ResolutionEffect { state, context ->
                            counterSpell(state, context.targets.single(), context.source)
                        },
                ),
                SpellMode(
                    text = "Destroy target ${hosed.blastWord} permanent.",
                    targetSpec = TargetSpec.TargetPermanent(hosed.permanentRestriction),
                    resolution =
                        ResolutionEffect { state, context ->
                            destroy(state, targetedPermanent(context.targets, name))
                        },
                ),
            )
    }

/**
 * An **effect-conditional** Blast (CR 608.2c): "Counter target spell if it's &lt;colour&gt;" / "Destroy
 * target permanent if it's &lt;colour&gt;". [hosed] appears only inside the resolutions, as
 * [targetIsColor]; both targeting lines are unrestricted, so every spell on the stack and every permanent
 * on the battlefield is a legal choice.
 *
 * The condition is tested **at resolution**, against the object as it is then — not against the object as
 * it was when targeted. Nothing in the pool changes an object's colour, so the two coincide today; the
 * test is written the CR's way regardless, because the alternative would be to snapshot a characteristic
 * the CR reads live.
 *
 * A target that has left its zone answers "not that colour" and the effect does nothing — but it never
 * gets that far, because CR 608.2b fizzles the spell first. Both paths lead to "nothing happens", by
 * different rules and with different log lines (`SpellFizzled` versus `SpellResolved`), and the
 * distinction is worth keeping: only one of them is the card working as printed.
 */
private fun conditionalBlast(
    name: String,
    cost: String,
    hosed: Color,
): SpellDefinition =
    object : ModalSpell {
        override val characteristics = blastCharacteristics(name, cost, CardType.INSTANT)
        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Counter target spell if it's ${hosed.blastWord}.",
                    targetSpec = TargetSpec.SpellOnStack(SpellRestriction.Any),
                    resolution =
                        ResolutionEffect { state, context ->
                            val target = context.targets.single()
                            if (targetIsColor(state, target, hosed)) {
                                counterSpell(state, target, context.source)
                            } else {
                                state
                            }
                        },
                ),
                SpellMode(
                    text = "Destroy target permanent if it's ${hosed.blastWord}.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT),
                    resolution =
                        ResolutionEffect { state, context ->
                            val target = context.targets.single()
                            if (targetIsColor(state, target, hosed)) {
                                destroy(state, targetedPermanent(listOf(target), name))
                            } else {
                                state
                            }
                        },
                ),
            )
    }

/** The colour word a Blast prints in its oracle text (CR 105.1). */
private val Color.blastWord: String
    get() =
        when (this) {
            Color.WHITE -> "white"
            Color.BLUE -> "blue"
            Color.BLACK -> "black"
            Color.RED -> "red"
            Color.GREEN -> "green"
        }

/**
 * The "target &lt;colour&gt; permanent" restriction a Blast's destroy mode names (CR 115.1b). Only the two
 * colours the pool's Blasts hose are expressible: [PermanentRestriction] carries a member per printed
 * targeting line, and no card prints the other three, so asking for one is a card-definition defect.
 */
private val Color.permanentRestriction: PermanentRestriction
    get() =
        when (this) {
            Color.RED -> PermanentRestriction.RED_PERMANENT
            Color.BLUE -> PermanentRestriction.BLUE_PERMANENT
            Color.WHITE, Color.BLACK, Color.GREEN ->
                error("CR 115.1b: no card in the pool prints \"target $blastWord permanent\"")
        }

/** The printed characteristics shared by the five: a plain single-colour instant with no P/T box. */
private fun blastCharacteristics(
    name: String,
    cost: String,
    type: CardType,
): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = ManaCost.parse(cost),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(type),
        subtypes = persistentSetOf(),
        powerToughness = null,
    )
