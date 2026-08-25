package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's colourless utility artifacts: Grixis Affinity's and Jund Wildfire's Ichor Wellspring,
 * and Monster Tron's Expedition Map. Both are artifacts whose whole printed text is a trigger or a
 * search, with no mana ability of their own — which, as it turns out, is exactly the property that
 * makes them safe to encode today (see the two absences below).
 *
 * Every mechanism is a published DSL primitive (ADR-003): the enters-the-battlefield and
 * put-into-a-graveyard triggers (CR 603.6a–b), [drawCards], a composite [ActivatedAbility] cost
 * (CR 602.1), and the CR 701.18 [LibrarySearch] clause. One small extension landed with them and is
 * called out in the packet report: [LibrarySearchFilter.LAND_CARD], the widest of the three land
 * filters, which Expedition Map is the first card to print.
 *
 * **Two siblings were deliberately absent, and the reason was an engine defect rather than a missing
 * primitive.** Bonder's Ornament ("{T}: Add one mana of any color. {4}, {T}: …") and Haunted Fengraf
 * ("{T}: Add {C}. {3}, {T}, Sacrifice this land: …") would each be the pool's first permanent that is
 * *both* a mana source *and* the source of a `{T}`-costed activated ability with a mana component.
 * `enumeratePaymentPlans` did not know which source was paying, so it offered a plan that tapped the
 * permanent for mana — after which the ability's own `{T}` cost could not be paid and the engine threw.
 * That was an enumerated-but-illegal action (ADR-005), and its fix belonged to the mana-payment
 * machinery, not to a card definition.
 *
 * **`FW-MANACOST` made that fix**, and Haunted Fengraf has since landed (GraveyardHate.kt) with exactly
 * the shape described above, driven end-to-end in the acceptance module. Bonder's Ornament is still
 * absent, now for a different and smaller reason: "add one mana of any color" is a production shape
 * [ManaAbility] does not have.
 *
 * Lotus Petal is absent for a different reason: its cost is `{T}` **and** sacrifice, which
 * [dev.mtgplay.core.definition.ManaAbility.viaSacrifice] cannot express — that flag means sacrifice
 * *instead of* tapping, and it makes a tapped source usable (docs/gauntlet-card-triage.md trap T2).
 */

/** The cards Ichor Wellspring draws on each half of its trigger (CR 120.1). */
const val ICHOR_WELLSPRING_DRAW: Int = 1

/**
 * The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3): the rules
 * engine moves it from the stack onto the battlefield. Shared by this file's artifacts, whose printed
 * work is a triggered or activated ability, never a resolution instruction.
 */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/**
 * Ichor Wellspring — `{2}` Artifact. "When this artifact enters or is put into a graveyard from the
 * battlefield, draw a card."
 *
 * One printed ability with **two** trigger conditions, encoded as two [TriggeredAbility] entries. That
 * is not an approximation: the two events are disjoint (a permanent cannot simultaneously enter the
 * battlefield and leave it for a graveyard), so exactly one of the pair can ever match a given event
 * and the observable behaviour — one trigger on the stack, one card drawn — is identical to the single
 * two-condition ability the card prints. Encoding only one condition would delete half the card, and
 * it is the *second* half the affinity lists are built around.
 *
 * Both halves are published conditions (CR 603.6a, CR 603.6b) and both function from the battlefield:
 * the second is a leaves-the-battlefield trigger, matched against the state just before the artifact
 * left (CR 603.10), which is why an Ichor Wellspring destroyed by an opposing Ancient Grudge still
 * draws. Every sacrifice outlet in those decks turns it into two cards; none of those outlets is
 * encoded yet, so today the second half is reached by a destroy effect and by the CR 704.5 state-based
 * actions.
 */
val ichorWellspring: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Ichor Wellspring",
                manaCost = ManaCost.parse("{2}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, ICHOR_WELLSPRING_DRAW)
                        },
                ),
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, ICHOR_WELLSPRING_DRAW)
                        },
                ),
            )
    }

/**
 * Expedition Map — `{1}` Artifact. "{2}, {T}, Sacrifice this artifact: Search your library for a land
 * card, reveal it, put it into your hand, then shuffle."
 *
 * Monster Tron's whole plan in one card, and the first client of [LibrarySearchFilter.LAND_CARD]. The
 * three-part cost [AbilityCost.Mana]`({2})` + [AbilityCost.TapSelf] + [AbilityCost.SacrificeSelf] is
 * paid on activation (CR 602.2b), so the Map is already in the graveyard by the time the search
 * resolves — which is why the ordinary [ActivatedAbility.effect] is a no-op and the whole resolution is
 * the [LibrarySearch] clause the engine orchestrates (CR 701.18): it pauses for the find-one choice,
 * reveals the found card, puts it into the hand, and shuffles through the match PRNG (ADR-006).
 *
 * **"A land card" is the widest of the three land filters in the pool.** Ash Barrens' basic landcycling
 * demands the Basic supertype and Lórien Revealed's islandcycling demands the Island land type; this
 * demands only the card type (CR 205.2), so a Great Furnace, an Urza land, and a plain Mountain are all
 * legal finds. Failing to find is always legal when searching your own library (CR 701.18b), and the
 * library is shuffled either way.
 *
 * The Map has **no mana ability of its own**, which is what keeps it clear of the payment-enumeration
 * defect recorded at the top of this file: nothing can plan to tap it for mana and then fail to pay its
 * own `{T}`.
 */
val expeditionMap: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Expedition Map",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect = ResolutionEffect { state, _ -> state },
                    librarySearch = LibrarySearch(LibrarySearchFilter.LAND_CARD),
                ),
            )
    }
