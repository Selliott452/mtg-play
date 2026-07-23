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
 */
data class ResolutionContext(
    val controller: PlayerId,
    val targets: PersistentList<Target>,
    val amount: Int = 0,
    val subject: ObjectId? = null,
    val discardedForCost: PersistentList<CardRef> = persistentListOf(),
)
