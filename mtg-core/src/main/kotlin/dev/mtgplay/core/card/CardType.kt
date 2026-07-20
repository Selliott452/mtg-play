package dev.mtgplay.core.card

/**
 * The card types (CR 300.1): artifact, battle, conspiracy, creature, dungeon, enchantment,
 * instant, kindred, land, phenomenon, plane, planeswalker, scheme, sorcery, and vanguard.
 *
 * The full CR 300.1 list is modeled — including the types used only by casual supplements — so
 * this vocabulary never has to shift underneath the rules code. The MVP pool itself exercises
 * only artifact, creature, enchantment, instant, land, and sorcery.
 */
enum class CardType {
    /** Artifact (CR 301). */
    ARTIFACT,

    /** Battle (CR 310); unused by the MVP pool. */
    BATTLE,

    /** Conspiracy, a casual-supplement card type; unused by the MVP pool. */
    CONSPIRACY,

    /** Creature (CR 302). */
    CREATURE,

    /** Dungeon, a casual-supplement card type; unused by the MVP pool. */
    DUNGEON,

    /** Enchantment (CR 303). */
    ENCHANTMENT,

    /** Instant (CR 304). */
    INSTANT,

    /** Kindred (CR 308, formerly "tribal"); unused by the MVP pool. */
    KINDRED,

    /** Land (CR 305). */
    LAND,

    /** Phenomenon, a casual-supplement card type; unused by the MVP pool. */
    PHENOMENON,

    /** Plane, a casual-supplement card type; unused by the MVP pool. */
    PLANE,

    /** Planeswalker (CR 306); unused by the MVP pool. */
    PLANESWALKER,

    /** Scheme, a casual-supplement card type; unused by the MVP pool. */
    SCHEME,

    /** Sorcery (CR 307). */
    SORCERY,

    /** Vanguard, a casual-supplement card type; unused by the MVP pool. */
    VANGUARD,
}
