package dev.mtgplay.core.card

/**
 * The CR 205.3 categorisation of the subtype words this engine knows: which of them are **creature
 * types** (CR 205.3m) and which belong to some other card type's subtype list. Additive, flagged core
 * (the keyword-tail packet).
 *
 * It exists for exactly one rule, and would not exist without it. [Keyword.CHANGELING] means "this
 * card is every creature type" (CR 702.73a) — *every creature type*, not every subtype. A changeling
 * is an Elf, a Goblin and a Dragon; it is **not** a Forest, **not** a Mountain, **not** an Aura and
 * **not** a Food. [Subtype] is deliberately a value class over the printed word (its own KDoc argues
 * the space is too large for an enum) and therefore carries no category, so the changeling answer had
 * nowhere to ask "is this word a creature type?" until here.
 *
 * **Getting this wrong is silent, and reachable.** Gingerbread Cabin enters untapped only if you
 * control three or more other Forests; Fireblast's alternative cost sacrifices two Mountains; Utopia
 * Sprawl enchants a Forest. A changeling that answered yes to every subtype would satisfy all three,
 * and every one of those is a wrong result that looks right (PLAN.md §7). So the classification is not
 * a nicety — it is the whole difference between changeling being implemented and being a bug.
 *
 * **Closed, and loud where it is open.** The two lists below are the subtype words the card pool
 * actually prints or names in a filter, split by CR 205.3. [Subtype.isCreatureType] fails loudly on a
 * word in neither: guessing a category would put the silent wrongness back exactly where this file
 * removes it. `mtg-cards` owns a test asserting every subtype its registry prints is classified here,
 * so a new card that forgets to add its word breaks the build rather than a future game.
 *
 * The vocabularies are written as newline-delimited text rather than argument lists because several
 * land types are two words (`Urza's Power-Plant`), which no whitespace-separated form survives.
 */
object CreatureType {
    /**
     * The creature types (CR 205.3m) the pool knows. A changeling is every one of these, and — the
     * property that makes the list safe to grow — adding a word here can only ever *widen* what a
     * changeling matches, never change what a non-changeling matches.
     */
    val CREATURE_TYPES: Set<Subtype> =
        subtypesOf(
            """
            Ape
            Bear
            Beast
            Bird
            Detective
            Artificer
            Bear
            Beast
            Bird
            Cat
            Dragon
            Drake
            Drone
            Druid
            Dryad
            Eldrazi
            Elemental
            Elf
            Faerie
            Flagbearer
            Giant
            Goblin
            Hydra
            Golem
            Homunculus
            Horror
            Human
            Illusion
            Knight
            Merfolk
            Monkey
            Myr
            Nightmare
            Ninja
            Ouphe
            Pirate
            Plant
            Ranger
            Rat
            Rhino
            Robot
            Rogue
            Scientist
            Scout
            Serpent
            Shaman
            Shapeshifter
            Skeleton
            Soldier
            Spawn
            Spirit
            Toy
            Treefolk
            Troll
            Utrom
            Vampire
            Wall
            Warrior
            Wizard
            Wurm
            Zombie
            """,
        )

    /**
     * The subtype words the pool knows that are **not** creature types: land types (CR 205.3i),
     * artifact types (CR 205.3g), enchantment types (CR 205.3h) and spell types (CR 205.3k). Listed
     * rather than inferred so that "not a creature type" is an assertion the pool makes out loud, and
     * an unlisted word is an unclassified word rather than a silent non-creature.
     *
     * **Adventure** and **Omen** are the spell types (CR 205.3k): the subtype an adventurer or omen
     * card's inset frame prints on its `Sorcery — Adventure` / `Sorcery — Omen` line (`W10-B`). They
     * are listed here rather than left out because a changeling adventurer would otherwise be asked
     * whether it is an Adventure and the engine would have to guess.
     *
     * Note the ones that read like creature types and are not: **Food**, **Blood** and **Clue** are
     * artifact types (Gingerbrute is an `Artifact Creature — Food Golem`, where `Food` is the artifact
     * half and `Golem` the creature half).
     */
    val NON_CREATURE_TYPES: Set<Subtype> =
        subtypesOf(
            """
            Adventure
            Aura
            Blood
            Clue
            Desert
            Equipment
            Food
            Forest
            Gate
            Island
            Map
            Mountain
            Omen
            Plains
            Spacecraft
            Swamp
            Treasure
            Urza's Mine
            Urza's Power-Plant
            Urza's Tower
            Vehicle
            """,
        )
}

/** The subtypes named one per line in [vocabulary], in listed order, ignoring blank lines. */
private fun subtypesOf(vocabulary: String): Set<Subtype> =
    vocabulary
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapTo(LinkedHashSet(), ::Subtype)

/**
 * Whether this subtype is a **creature type** (CR 205.3m) — the question [Keyword.CHANGELING] turns
 * on, and the only question this classification exists to answer.
 *
 * Fails loudly on a word in neither of [CreatureType]'s lists. That is deliberate and is the whole
 * safety property: the alternatives are to answer `true` (a changeling becomes a Forest) or `false`
 * (a changeling stops being an Elf), and both are the wrong-result-that-looks-right this engine
 * refuses to ship (CONVENTIONS.md, PLAN.md §7).
 */
fun Subtype.isCreatureType(): Boolean =
    when (this) {
        in CreatureType.CREATURE_TYPES -> true
        in CreatureType.NON_CREATURE_TYPES -> false
        else ->
            error(
                "CR 205.3: the subtype \"$value\" is not classified as a creature type or a " +
                    "non-creature type, so whether Keyword.CHANGELING grants it (CR 702.73a) cannot " +
                    "be decided; add it to CreatureType rather than letting the engine guess",
            )
    }
