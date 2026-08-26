package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.card.Subtype
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * One continuous effect a permanent's static ability generates (CR 604.3, CR 611.2), expressed as
 * card-definition data — an Aura's "enchanted X gets/has …". Additive, flagged core (P4.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This is the *declaration* of
 * what the ability does; the *classification into CR 613 layers* and the *application* are rules
 * logic and live in `mtg-rules`. Core says a modifier is +N/+N and grants a keyword; rules decides
 * that grants are layer 6 and P/T modifiers are layer 7c (docs/design/layer-system.md §2).
 *
 * The effect is active exactly while its source permanent is on the battlefield, its [affects] set is
 * non-empty — for an Aura, while it is attached to a legal object (CR 604.3) — and its [condition], if
 * it has one, holds. It carries no timestamp or duration: the timestamp is the source's
 * battlefield-entry order and duration is uniformly "while the static ability is active"
 * (docs/design/layer-system.md §2, §3). A *resolution*-generated effect with a duration is the other
 * generator entirely and lives in [dev.mtgplay.core.state.TimedContinuousEffect]
 * (docs/design/duration.md).
 *
 * @property affects which objects the effect modifies (CR 611.2c); [AffectedSet.Enchanted] for every
 *   Aura — the one object it enchants — and [AffectedSet.Self] for a permanent whose static ability
 *   modifies only itself (`FW-CONDSTATIC`).
 * @property condition the "as long as …" clause gating the whole effect (CR 604.3), or `null` for an
 *   unconditional static ability. Additive, flagged (`FW-CONDSTATIC`) — Goblin Tomb Raider's "as long
 *   as you control an artifact". CR 604.3 makes this a *continuous* re-evaluation with no trigger and
 *   no stack: the effect stops applying the instant the condition fails and resumes the instant it
 *   holds again, which the compute-on-read layer engine gives for free (docs/design/layer-system.md §5)
 *   and which a triggered-ability encoding of the same text would get wrong.
 * @property grantedKeywords keyword abilities the affected object gains (CR 613.3 layer 6); the
 *   in-game keyword set unions these on (Rancor grants trample).
 * @property grantedManaAbilities mana abilities the affected object gains (CR 613.3 layer 6); how
 *   an Abundant-Growth-enchanted land gains "{T}: add one mana of any color".
 * @property grantedProtections protection abilities the affected object gains, one per quality
 *   (CR 613.3 layer 6, CR 702.16) — Mask of Law and Grace's "enchanted creature has protection from
 *   black and from red". A grant like any other keyword grant, and CR 613.1f's same layer; it is a
 *   field of its own only because protection carries a quality and [Keyword] cannot
 *   (docs/design/protection.md §4). Additive, flagged (`FW-PROTECT`).
 * @property addedCardTypes card types the affected object **gains** in CR 613 layer 4 (CR 613.1d) —
 *   Pinnacle Kill-Ship's "it's an artifact **creature** at 7+". Additive, flagged core (`W10-C`).
 *
 *   **The static declaration's own KDoc said this field would never exist, and that was wrong rather
 *   than merely outdated.** `FW-TYPECHANGE` recorded that "every type change in the gauntlet pool is
 *   printed on a resolving *ability*, never on a permanent's static ability", and made
 *   [dev.mtgplay.core.state.ContinuousModification] the sole generator of a layer-4 change. A
 *   Spacecraft's is the counterexample: nothing resolves, no ability goes on the stack, and the type
 *   change starts and stops with a counter count (CR 604.3). The prediction attached to that note held
 *   exactly as written — the field arrived here, `staticEffectsOn` threads it, and nothing below
 *   `ActiveEffect` changed.
 *
 *   **Gains, never replaces** (CR 205.1b), for the reason the timed counterpart gives: the Spacecraft
 *   stays an artifact. There is deliberately no `removedCardTypes` here either, and the reason is the
 *   same one and no weaker — no card in the gauntlet prints the removing form on a static ability, so
 *   the field would be an always-empty branch of the layer-4 application.
 * @property removedCardTypes card types the affected object **loses** in CR 613 layer 4 (CR 613.1d) —
 *   bestow's "it's an Aura enchantment **and not a creature**" (CR 702.103a). Additive, flagged core
 *   (`W10-C`).
 *
 *   **The first removing type change in the engine**, and the field two packets declined to add for the
 *   stated reason that no card printed the form. Bestow prints it, and it is not decoration: while a
 *   bestowed permanent is an Aura it must not be able to attack, block, be targeted by removal that says
 *   "creature", or die to a sweeper. Adding the Aura subtype without removing the creature type would
 *   leave a 0/1 creature sitting in the combat enumeration, which is an enumerated-but-illegal action
 *   (ADR-005) and the exact failure the union-only field was safe from only while nothing needed it.
 *
 *   Removal is applied **after** addition within layer 4, which is unobservable for every card that can
 *   currently be written (no effect both adds and removes the same type) and is stated so that the day
 *   one can, the answer is on the record rather than in the fold's iteration order.
 * @property addedSubtypes subtypes the affected object gains in CR 613 layer 4 (CR 613.1d, CR 205.3) —
 *   bestow's **Aura**. Additive, flagged core (`W10-C`). CR 613.1d is one layer for card types and
 *   subtypes alike; this is a separate field only because a card may change one without the other, which
 *   Pinnacle Kill-Ship (types, no subtype) and bestow (both) demonstrate in the same pool.
 * @property powerMod the layer-7c power modifier (CR 613.3 sublayer 7c), possibly [Magnitude.Zero].
 * @property toughnessMod the layer-7c toughness modifier (CR 613.3 sublayer 7c), possibly
 *   [Magnitude.Zero].
 */
data class StaticContinuousEffect(
    val affects: AffectedSet = AffectedSet.Enchanted,
    val addedCardTypes: PersistentSet<CardType> = persistentSetOf(),
    val removedCardTypes: PersistentSet<CardType> = persistentSetOf(),
    val addedSubtypes: PersistentSet<Subtype> = persistentSetOf(),
    val grantedKeywords: PersistentSet<Keyword> = persistentSetOf(),
    val grantedManaAbilities: PersistentList<ManaAbility> = persistentListOf(),
    val grantedProtections: PersistentSet<Quality> = persistentSetOf(),
    val powerMod: Magnitude = Magnitude.Zero,
    val toughnessMod: Magnitude = Magnitude.Zero,
    val condition: StaticCondition? = null,
)
