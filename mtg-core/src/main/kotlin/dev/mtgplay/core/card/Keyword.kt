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
 * All seven change engine behaviour: [FLYING], [FIRST_STRIKE], and [VIGILANCE] since P3.1;
 * [TRAMPLE], [HEXPROOF], and [LIFELINK] since P5.3 (the trample assignment decision, the targeting
 * restriction on enumeration, and the damage-result lifegain, respectively); and [DEVOID], which
 * alone among them changes a characteristic rather than combat — it makes
 * [PrintedCharacteristics.colors] colorless (CR 105.2, CR 702.114a) instead of deriving colour from
 * the mana cost.
 * restriction on enumeration, and the damage-result lifegain, respectively); [INDESTRUCTIBLE] since
 * P8.4 (the CR 704.5g lethal-damage exemption).
 */
enum class Keyword {
    /** Flying (CR 702.9): can be blocked only by creatures with flying or reach. */
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
     * damage do not destroy it (CR 702.12b). It is *not* general protection from dying: a
     * toughness-0-or-less permanent still goes to the graveyard (CR 704.5f), because that is not
     * destruction (see the `CreatureDeathCause` distinction in `mtg-rules`).
     *
     * The Bridge artifact lands print it. The only destruction the engine performs is the CR 704.5g
     * lethal-damage state-based action, which honours this (P8.4); a *land* therefore never reaches a
     * path where it matters today, and when a "destroy target permanent" effect first lands it must
     * consult the effective-keyword accessor rather than re-deriving destruction.
     */
    INDESTRUCTIBLE,
}
