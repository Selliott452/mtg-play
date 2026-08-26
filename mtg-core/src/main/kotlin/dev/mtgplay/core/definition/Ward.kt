package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * The **ward** keyword ability (CR 702.21a) — Tolarian Terror's `Ward {2}`. Additive, flagged core
 * (`FW-WARD`, docs/design/countering-spells.md §13, which reserved this slot as a non-goal).
 *
 * CR 702.21a: *"Ward [cost]" means "Whenever this permanent becomes the target of a spell or ability an
 * opponent controls, counter that spell or ability unless that player pays [cost]."* So ward is a
 * **static** ability that grants a triggered one, and the engine synthesizes the trigger from this
 * declaration — the same split ninjutsu uses, and for the same reason: the ability text is the
 * mechanic's, identical on every card that prints it, so restating it per card would be thirteen chances
 * to get it wrong.
 *
 * **It cannot be a [dev.mtgplay.core.card.Keyword].** That type is an enum, and every member of it is a
 * bare word; ward carries a cost. A `WARD_2` member would be an enum that means a number, and the second
 * printing at a different cost would need a second member.
 *
 * Three rules facts the shape has to preserve, each of which a narrower encoding would delete:
 *
 * - **"a spell *or ability*"**, so a targeted activated or triggered ability an opponent controls
 *   triggers ward exactly as a removal spell does — and countering an ability is a different action from
 *   countering a spell (CR 113.7a: an ability on the stack is not a card and goes nowhere when
 *   countered).
 * - **"becomes the target"**, which is CR 601.2c for a spell and CR 602.2b / CR 603.3d for an ability —
 *   *while it is being put on the stack*, and therefore **before its controller has paid for it**. An
 *   opponent who taps out for a removal spell watches it get countered, and that is the card.
 * - **"unless *that player* pays"** — the decider is the controller of the targeting object, not ward's
 *   controller, and paying makes the ward trigger resolve having done nothing.
 *
 * @property cost the mana the targeting object's controller must pay to save it (CR 702.21a).
 */
data class Ward(
    val cost: ManaCost,
)
