package dev.mtgplay.core.definition

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.DamageSource
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
 * @property sourceCard the printed identity behind [source] (CR 113.7c last-known information), or
 *   `null` where the engine has none. Additive, flagged core (`FW-PREVENT`,
 *   docs/design/protection.md §3 Part A). [source] alone is not enough to build a
 *   [dev.mtgplay.core.state.DamageSource]: an object's characteristics are looked up by [CardRef]
 *   through the definition registry, which works whatever zone the source has reached by the time
 *   its damage lands, while its id may name nothing anywhere (CR 400.7).
 * @property kicked whether the resolving spell's **kicker** cost was paid (CR 702.33a), the linked
 *   information CR 702.33f makes readable; `false` for a spell without kicker, for one whose kicker was
 *   declined, and for every ability. Additive, flagged core (`FW-OPTCOST`). Prohibit's resolution reads
 *   it to decide whether it counters a mana value of 2 or of 4, and it is supplied from
 *   [dev.mtgplay.core.state.StackEntry.Spell.kicked].
 * @property chosenX the value announced for the resolving spell's variable symbol (CR 107.3, CR 601.2b),
 *   supplied from [dev.mtgplay.core.state.StackEntry.Spell.chosenX]; `0` for every spell whose cost
 *   carries none and for every ability. Additive, flagged core (`FW-X`). This is the number an "X damage"
 *   or "X counters" resolution deals or places, and it is read from the cast record rather than
 *   recomputed from the cost, because the printed cost's X is zero everywhere but the stack (CR 202.3b).
 * @property costPowerSource what was named to pay a **non-consuming** additional cost (CR 601.2b), or
 *   `null` for every spell without one and for every ability. Additive, flagged core (`W9-D`). The linked
 *   information Monstrous Emergence's resolution reads — "the power of the creature you chose or the card
 *   you revealed" — supplied from [dev.mtgplay.core.state.StackEntry.Spell.costPowerSource].
 *
 *   Unlike [sacrificedForCost] this is **not** last-known information at all for one of its two members:
 *   a chosen creature is still on the battlefield, so what the record carries is a *handle* the resolution
 *   re-reads live (CR 608.2h), not a captured value. See [dev.mtgplay.core.state.ChosenPowerSource] for
 *   why the other member is a printed [CardRef] instead.
 * @property tappedForCost the battlefield permanents tapped to pay this **ability's**
 *   [AbilityCost.TapPermanentYouControl] component (CR 602.2b), in the order tapped; empty for every
 *   spell and for every ability with no such cost. Additive, flagged core (`W10-C`). The linked
 *   information Pinnacle Kill-Ship's Station reads — "put charge counters equal to **its** power on
 *   this Spacecraft" — supplied from
 *   [dev.mtgplay.core.state.StackEntry.ActivatedAbilityOnStack.tappedForCost].
 *
 *   Live [ObjectId] handles rather than printed [CardRef]s, for [costPowerSource]'s reason and not
 *   [sacrificedForCost]'s: the tapped permanent is alive on the battlefield when the ability resolves,
 *   so CR 608.2h reads its power then. Wrap one in
 *   [dev.mtgplay.core.state.ChosenPowerSource.ChosenCreature] to read it through the published
 *   power primitive, which handles the CR 113.7a case where it has since left.
 * @property linkedExiled the exile objects a **linked** ability (CR 607.2) of this ability's source put
 *   into exile, in the order exiled; empty for every spell and for an ability with no linked partner.
 *   Additive, flagged core (`FW-LINKEDEXILE`, docs/design/exile-and-return.md §4). The linked
 *   information Journey to Nowhere's second ability reads ("return **the exiled card**") and Mesmeric
 *   Fiend's ("return the exiled card to its owner's hand"), supplied from
 *   [dev.mtgplay.core.state.PendingTrigger.linkedExiled].
 *
 *   Like [sacrificedForCost] this is captured rather than looked up, and for the sharper version of the
 *   same reason: the source permanent has *already left the battlefield* when the ability that reads
 *   this resolves, so there is nothing left to read the link off (CR 603.10). Unlike
 *   [sacrificedForCost] it is the live [ObjectId] rather than a printed [CardRef], because the effect
 *   must move that exact exile object and not merely name a card.
 */
data class ResolutionContext(
    val controller: PlayerId,
    val targets: PersistentList<Target>,
    val amount: Int = 0,
    val subject: ObjectId? = null,
    val discardedForCost: PersistentList<CardRef> = persistentListOf(),
    val source: ObjectId? = null,
    val sacrificedForCost: PersistentList<CardRef> = persistentListOf(),
    val sourceCard: CardRef? = null,
    val linkedExiled: PersistentList<ObjectId> = persistentListOf(),
    val kicked: Boolean = false,
    val chosenX: Int = 0,
    val optionalCostPaid: Boolean = false,
    val costPowerSource: ChosenPowerSource? = null,
    val tappedForCost: PersistentList<ObjectId> = persistentListOf(),
) {
    /**
     * The [dev.mtgplay.core.state.DamageSource] this resolving object is, for the damage primitives
     * (CR 120.1). Fails loudly when the context carries no [sourceCard]: a resolution that deals
     * damage without knowing what dealt it cannot be checked against CR 615 prevention or
     * CR 702.16e protection, and guessing a source would make both silently wrong.
     */
    fun damageSource(): DamageSource =
        DamageSource(
            objectId = source,
            card =
                sourceCard ?: error(
                    "CR 120.1: damage has a source, but this resolution context carries no " +
                        "sourceCard; every damage-dealing resolution must be given one",
                ),
        )
}
