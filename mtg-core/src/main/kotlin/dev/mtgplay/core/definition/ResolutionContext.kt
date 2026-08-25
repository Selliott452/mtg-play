package dev.mtgplay.core.definition

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * What a resolving spell or ability knows beyond the game state (CR 608.2): who controls it, what
 * it targets, and — for a triggered ability — the trigger's linked information.
 *
 * The casting pipeline records the controller and targets on the stack entry at cast time
 * (CR 601.2c) and hands them to the [ResolutionEffect] here; the trigger framework (P5.1) reuses the
 * same effect signature for a triggered ability, supplying [amount] and [subject] from the fired
 * trigger's captured last-known information ([dev.mtgplay.core.state.PendingTrigger]). Phase 5 grows
 * this record — not the effect signature — with a cast's linked information (what was discarded to an
 * additional cost, the chosen mode), per the docs/decklists.md design consequence that cost-payment
 * results are part of the spell's cast record.
 *
 * @property controller the spell's or ability's controller (CR 601.2 / CR 603.3d).
 * @property targets the chosen targets, in the order chosen; empty for an untargeted spell or
 *   ability. On resolution every entry is still legal — a spell whose targets have all become illegal
 *   never resolves (CR 608.2b), so the effect never sees one.
 * @property amount the resolving object's numeric linked information (CR 118.9), e.g. the damage an
 *   enchanted creature dealt for Armadillo Cloak's "gain that much life"; `0` when there is none
 *   (every spell in the pool).
 * @property subject a specific object the effect acts on beyond its targets (CR 603.10), e.g. the
 *   graveyard object Rancor returns to its owner's hand; `null` when the effect acts on no such
 *   object (every spell in the pool).
 * @property discardedForCost the printed identities of the cards discarded to an additional discard
 *   cost (CR 601.2b), in the order discarded; empty for a spell with no such cost. Additive, flagged
 *   core (P6.2a). The linked information Grab the Prize's resolution reads ("if the discarded card
 *   wasn't a land card"); supplied from [dev.mtgplay.core.state.StackEntry.Spell.discardedForCost].
 * @property sacrificedForCost the printed identities of the permanents sacrificed to a sacrifice
 *   additional cost (CR 601.2b/h), in the order sacrificed; empty for a spell with no such cost.
 *   Additive, flagged core (`FW-ADDSAC`). The linked information Reckoner's Bargain's resolution reads
 *   ("you gain life equal to the sacrificed permanent's mana value"); supplied from
 *   [dev.mtgplay.core.state.StackEntry.Spell.sacrificedForCost].
 *
 *   It is the **printed** identity rather than the battlefield object, and that is the CR's own answer:
 *   the permanent no longer exists when the spell resolves, so the value is read from last-known
 *   information (CR 608.2h, CR 113.7a) — captured as the cost was paid, not looked up afterwards.
 * @property source the resolving object's **own** identity: a spell's stack-residence object id
 *   (CR 400.7), an activated or triggered ability's source object id as of activation or firing
 *   (CR 113.7c last-known information), or `null` where the engine has none to give. Additive, flagged
 *   core (`FW-COUNTER`, docs/design/countering-spells.md §2). An effect needs this the moment its
 *   outcome names the object performing it rather than only the object performed upon — countering,
 *   whose `SpellCountered` event narrates *what* countered *what*. Distinct from [subject], which is a
 *   further object the effect acts *on*.
 */
data class ResolutionContext(
    val controller: PlayerId,
    val targets: PersistentList<Target>,
    val amount: Int = 0,
    val subject: ObjectId? = null,
    val discardedForCost: PersistentList<CardRef> = persistentListOf(),
    val source: ObjectId? = null,
    val sacrificedForCost: PersistentList<CardRef> = persistentListOf(),
)
