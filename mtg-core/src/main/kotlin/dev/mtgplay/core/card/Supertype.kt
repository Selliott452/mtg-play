package dev.mtgplay.core.card

/**
 * The supertypes (CR 205.4): basic, legendary, ongoing, snow, and world.
 *
 * [BASIC] is the load-bearing one for the MVP pool: Ash Barrens' basic landcycling searches for
 * a card with the basic supertype, and deck construction allows any number of basic lands.
 */
enum class Supertype {
    /** Basic (CR 205.4): the supertype of the basic lands; what "search for a basic land card" means. */
    BASIC,

    /** Legendary (CR 205.4); unused by the MVP pool. */
    LEGENDARY,

    /** Ongoing (CR 205.4), a scheme-card supertype; unused by the MVP pool. */
    ONGOING,

    /** Snow (CR 205.4); unused by the MVP pool. */
    SNOW,

    /** World (CR 205.4); unused by the MVP pool. */
    WORLD,
}
