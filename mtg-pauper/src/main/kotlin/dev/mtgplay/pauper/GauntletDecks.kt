package dev.mtgplay.pauper

/**
 * The thirteen Pauper gauntlet decklists, parsed from their bundled resources.
 *
 * The gauntlet is the opposition corpus the training environment measures a pilot against: thirteen
 * competitive 75s spanning aggro, tempo, control, ramp, and combo. Each list is transcribed
 * verbatim from the source gauntlet, mainboard 60 and sideboard 15 (CR 100.2a, CR 100.4a).
 *
 * These are decklists, not playable decks: a card without a [dev.mtgplay.core.definition.CardDefinition]
 * in `mtg-cards` is inert (ADR-009), so [DefinitionCoverage] is what says how much of each deck the
 * engine can actually play. The burn-down that number describes is the card-encoding backlog.
 *
 * **Two of these diverge from the MVP fixtures of the same name.** [MvpDecks] holds the pinned
 * `docs/decklists.md` versions of Mono-Red Madness and GW Bogles that the acceptance suite runs;
 * [monoRedMadness] and [gwBogles] here are the gauntlet's *current* versions, which have moved on.
 * The two pairs are deliberately kept separate until the divergent cards are encoded.
 */
object GauntletDecks {
    private const val RESOURCE_DIRECTORY = "/decks/gauntlet"

    /** Elves — green creature-combo ramp on Priest of Titania and Timberwatch Elf. */
    val elves: DeckList by lazy { load("elves", "Elves") }

    /** Gates — the Basilisk Gate midrange pile. */
    val gates: DeckList by lazy { load("gates", "Gates") }

    /** Grixis Affinity — artifact-lands aggro on Myr Enforcer and Refurbished Familiar. */
    val grixisAffinity: DeckList by lazy { load("grixis-affinity", "Grixis Affinity") }

    /**
     * GW Bogles — the gauntlet's hexproof-aura deck. **Not** the [MvpDecks.gwBogles] fixture: this
     * list runs Kruphix's Insight, Wild Growth, and a maindeck Lifelink.
     */
    val gwBogles: DeckList by lazy { load("gw-bogles", "GW Bogles") }

    /** Jeskai Ephemerate — blink value on Ephemerate and Mulldrifter. */
    val jeskaiEphemerate: DeckList by lazy { load("jeskai-ephemerate", "Jeskai Ephemerate") }

    /** Jund Wildfire — Cleansing Wildfire artifact-lands midrange with removal. */
    val jundWildfire: DeckList by lazy { load("jund-wildfire", "Jund Wildfire") }

    /** Mono Blue Faeries — ninjutsu tempo on Spellstutter Sprite and Ninja of the Deep Hours. */
    val monoBlueFaeries: DeckList by lazy { load("mono-blue-faeries", "Mono Blue Faeries") }

    /** Mono-Blue Terror — Tolarian Terror / Cryptic Serpent cost-reduction tempo. */
    val monoBlueTerror: DeckList by lazy { load("mono-blue-terror", "Mono-Blue Terror") }

    /**
     * Mono-Red Madness — the gauntlet's burn/madness deck. **Not** the [MvpDecks.monoRedMadness]
     * fixture: this list runs Kessig Flamebreather and has dropped Melded Moxite.
     */
    val monoRedMadness: DeckList by lazy { load("mono-red-madness", "Mono-Red Madness") }

    /** Mono Red Rally — one-drop aggro on Rally at the Hornburg and Inventor's Axe. */
    val monoRedRally: DeckList by lazy { load("mono-red-rally", "Mono Red Rally") }

    /** Monster Tron — Urza-lands ramp into Maelstrom Colossus and Bramble Wurm. */
    val monsterTron: DeckList by lazy { load("monster-tron", "Monster Tron") }

    /** Spy Combo — Balustrade Spy self-mill into Dread Return and Lotleth Giant. */
    val spyCombo: DeckList by lazy { load("spy-combo", "Spy Combo") }

    /** UWX Familiar — Sunscape Familiar / Ghostly Flicker control. */
    val uwxFamiliar: DeckList by lazy { load("uwx-familiar", "UWX Familiar") }

    /** All thirteen gauntlet decklists, in the order the coverage report prints them. */
    val all: List<DeckList>
        get() =
            listOf(
                elves,
                gates,
                grixisAffinity,
                gwBogles,
                jeskaiEphemerate,
                jundWildfire,
                monoBlueFaeries,
                monoBlueTerror,
                monoRedMadness,
                monoRedRally,
                monsterTron,
                spyCombo,
                uwxFamiliar,
            )

    private fun load(
        fileName: String,
        deckName: String,
    ): DeckList = DeckListParser.parse(readResourceText("$RESOURCE_DIRECTORY/$fileName.deck"), deckName)
}
