package dev.mtgplay.core.card

/**
 * A printed keyword ability (CR 702) a card carries in its [PrintedCharacteristics].
 *
 * The MVP-minimal set: exactly the keywords the pinned pool prints or the combat engine
 * consults (docs/decklists.md). Modelled as **printed** characteristics — what the physical
 * card says (CR 702). In-game keywords are *computed* from these by the continuous-effect layer
 * system (CR 613, layer 6) in Phase 4, which grows the set as aura keyword grants arrive
 * (Ethereal Armor, Rancor, Armadillo Cloak). Nouns only: which keyword changes combat, and how,
 * is rules-engine territory (P3.1 onward).
 *
 * Combat consults these only through the documented effective-keyword accessor in `mtg-rules`
 * (never by reading a definition directly), so Phase 4 can reroute the read without touching the
 * combat rules.
 *
 * All eleven change engine behaviour: [FLYING], [FIRST_STRIKE], and [VIGILANCE] since P3.1;
 * [TRAMPLE], [HEXPROOF], and [LIFELINK] since P5.3 (the trample assignment decision, the targeting
 * restriction on enumeration, and the damage-result lifegain, respectively); [INDESTRUCTIBLE] since
 * P8.4 (the CR 704.5g lethal-damage exemption, and the CR 701.7a destroy effect since the removal
 * packet); [HASTE], [DEFENDER], and [REACH] since `FW-COUNTERS` (the summoning-sickness bypass, the
 * attack bar, and the flying-blocker permission, respectively); and [DEVOID], which alone among them
 * changes a characteristic rather than combat — it makes [PrintedCharacteristics.colors] colorless
 * (CR 105.2, CR 702.114a) instead of deriving colour from the mana cost.
 *
 * **No member is ever a bare enum entry.** A keyword joins this set with the rules effect it names,
 * honoured at every site that decides the thing it changes, and read only through the
 * effective-keyword accessor. Adding one without its effect would print a line on a card that the
 * engine silently does not play.
 */
enum class Keyword {
    /** Flying (CR 702.9): can be blocked only by creatures with flying or [REACH] (CR 702.9b). */
    FLYING,

    /** First strike (CR 702.7): deals combat damage before creatures without it (CR 510.5). */
    FIRST_STRIKE,

    /** Vigilance (CR 702.21): attacking does not cause the creature to tap (CR 508.1f). */
    VIGILANCE,

    /**
     * Trample (CR 702.19): a blocked attacker's controller may assign to the defending player any
     * combat damage above what is lethal to its blockers. Surfaces the P5.3 trample-assignment
     * decision; a blocked trampler whose blockers all left combat assigns all its damage to the
     * player (CR 702.19g).
     */
    TRAMPLE,

    /**
     * Hexproof (CR 702.11): can't be the target of spells or abilities an *opponent* controls. Its
     * controller's own spells and abilities target it freely. A targeting restriction on
     * enumeration (P5.3): an opponent's target enumeration excludes it.
     */
    HEXPROOF,

    /**
     * Lifelink (CR 702.15): damage this creature deals also causes its controller to gain that much
     * life, as a result of the damage — not a triggered ability, no stack (P5.3).
     */
    LIFELINK,

    /**
     * Devoid (CR 702.114a): "this object is colorless". A characteristic-defining ability, not a
     * combat one — it is the one member here the combat engine never consults. It works in every
     * zone (CR 604.3, CR 702.114a), so it is read off the printed characteristics directly:
     * [PrintedCharacteristics.colors] answers the empty set (CR 105.4) for a devoid card whatever
     * its mana cost says. Unfathomable Truths' `{4}{U}` is colorless.
     */
    DEVOID,

    /**
     * Indestructible (CR 702.12): the permanent can't be destroyed — "destroy" effects and lethal
     * damage do not destroy it (CR 702.12b). It is **not** general protection from dying: a
     * toughness-0-or-less permanent still goes to the graveyard (CR 704.5f), because that is not
     * destruction (see the `CreatureDeathCause` distinction in `mtg-rules`).
     *
     * The Bridge artifact lands print it. The engine destroys in exactly two places, and both honour
     * this through the one effective-keyword seam (`isIndestructible` in `EffectiveCharacteristics.kt`)
     * rather than re-deriving it: the CR 704.5g lethal-damage state-based action (P8.4), and the
     * CR 701.7a destroy effect (the removal-and-destruction packet). The second is what made the
     * keyword live on a Bridge — an opposing Ancient Grudge now finds one and destroys nothing.
     */
    INDESTRUCTIBLE,

    /**
     * Haste (CR 702.10): a static ability that lifts the CR 302.6 summoning-sickness restriction in
     * both directions it applies. A creature with haste **can attack** even though it has not been
     * controlled by its controller continuously since their most recent turn began (CR 702.10b), and
     * its controller **can activate its `{T}` abilities** under the same condition (CR 702.10c).
     * Multiple instances are redundant (CR 702.10d) — free, since the keyword set is a set. Added by
     * `FW-COUNTERS`.
     *
     * Three engine sites decide something CR 302.6 gates, and haste is honoured at all three through
     * the one effective-keyword seam: attacker eligibility (`eligibleAttackers`), the `{T}` component
     * of a non-mana activated ability's cost (`abilityCostPayable`), and mana-source usability
     * (`manaSourceUsable`) — the last of which is the *shared* predicate the payment planner and the
     * payment executor both call, so one honouring covers both halves of payment and they cannot
     * drift (docs/design/mana-payment.md §10).
     *
     * Clockwork Percussionist and Gingerbrute print it; Goblin Tomb Raider grants it conditionally.
     */
    HASTE,

    /**
     * Defender (CR 702.3): a static ability meaning "this creature can't attack" (CR 702.3b). Not an
     * evasion and not a blocking restriction — a creature with defender blocks normally; it is simply
     * never offered as an attacker (`eligibleAttackers`). Multiple instances are redundant
     * (CR 702.3c). Added by `FW-COUNTERS`.
     *
     * The Walls print it, and Overgrown Battlement's mana ability counts creatures that have it — a
     * keyword read that is not a combat read, and the reason the keyword had to exist before that
     * card's mana half could be encoded at all.
     */
    DEFENDER,

    /**
     * Reach (CR 702.17): a static ability letting the creature block a creature with flying
     * (CR 702.17b) — "a creature with flying can't be blocked except by creatures with flying and/or
     * reach". It grants no evasion of its own and changes nothing about attacking. Multiple instances
     * are redundant (CR 702.17c). Added by `FW-COUNTERS`.
     *
     * Reach satisfies **flying** and nothing else. It does *not* satisfy
     * [Evasion.BLOCKABLE_ONLY_BY_FLYING] (Silhana Ledgewalker's "can't be blocked except by creatures
     * with flying"), which demands flying literally; the two restrictions read alike and are not the
     * same, and `canBlock` in `mtg-rules` keeps them apart.
     */
    REACH,
}
