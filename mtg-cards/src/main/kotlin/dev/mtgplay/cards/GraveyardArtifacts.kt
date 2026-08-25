package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.OptionalManaThenDraw
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetPlayerExilesFromGraveyard
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.exileAllGraveyards
import dev.mtgplay.rules.effect.exileGraveyard
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's two colourless graveyard-hate artifacts, and the two blockers GraveyardHate.kt recorded
 * against them when Bojuka Bog landed. Both are now closed, and neither by a change to the exile
 * primitives — those were already right.
 *
 * **Nihil Spellbomb**'s activated half was described there as "exactly Bojuka Bog's clause on an
 * artifact", which it is. Its dies trigger — "you may pay {B}. If you do, draw a card" — was the
 * blocker: an optional *mana* cost inside a resolution, which `OptionalCostMode` (a discard or a land
 * sacrifice) cannot express, because a mana payment is a plan over mana sources rather than a selection
 * from a list of objects. That is now [OptionalManaThenDraw], a clause of its own.
 *
 * **Relic of Progenitus** needed two things: a decision made by a **non-controller** — "target player
 * exiles a card from their graveyard" is chosen by the *targeted* player (CR 701.3a), which is now
 * [TargetPlayerExilesFromGraveyard] — and an [AbilityCost] member for "{1}, Exile this artifact:", which
 * is now [AbilityCost.ExileSelf]. The third piece, "Exile all graveyards", is a published fold
 * ([exileAllGraveyards]) rather than something either card had to write.
 *
 * The old note also said an activated ability could not target. `FW-ABILTGT` landed since, so
 * [ActivatedAbility.targetSpec] carries "target player" on both cards below without any card-local
 * machinery.
 */

/** The mana Nihil Spellbomb's dies trigger offers to accept for its draw (CR 601.3b). */
private const val NIHIL_SPELLBOMB_DRAW_COST: String = "{B}"

/** The cards Nihil Spellbomb's dies trigger draws when its cost is paid (CR 120.1). */
const val NIHIL_SPELLBOMB_DRAW: Int = 1

/** The cards Relic of Progenitus' exile-itself ability draws (CR 120.1). */
const val RELIC_OF_PROGENITUS_DRAW: Int = 1

/**
 * Nihil Spellbomb — `{1}` Artifact. "{T}, Sacrifice this artifact: Exile target player's graveyard. When
 * this artifact is put into a graveyard from the battlefield, you may pay {B}. If you do, draw a card."
 *
 * **Two halves that chain, and the chain is the card.** The activated ability's cost sacrifices the
 * Spellbomb, so paying it *is* the event the second ability triggers on (CR 603.6b) — cracking the
 * Spellbomb both exiles a graveyard and offers the draw. Nothing in either declaration says so; it falls
 * out of the cost being paid at CR 602.2b, before the ability is even on the stack.
 *
 * **The order that follows is worth stating, because it is not the reading order.** The cost is paid
 * first, so the *dies trigger* goes on the stack **above** the exile ability and resolves **first**: the
 * draw is offered while the graveyard exile is still waiting. Against a graveyard containing a card that
 * would matter, that ordering is real information — and it is the rules' ordering, not a choice this
 * definition makes.
 *
 * **"Target player's graveyard" offers both seats** (CR 115.1a), including the Spellbomb's own
 * controller, exactly as Bojuka Bog's trigger does. A player stops being a legal target only by leaving
 * the game, which in a two-player game is the game ending (CR 104.2a), so this ability can never reach
 * the CR 608.2b fizzle.
 *
 * **The dies trigger fires however the artifact died** — sacrificed to its own ability, destroyed, or
 * sacrificed to something else (CR 603.6b names the zone change, not the cause). The ability's zone scope is
 * the battlefield default, which is right: the ability functions from the battlefield and triggers on
 * leaving it.
 *
 * A `{1}` artifact is a plain [SpellDefinition] cast at sorcery speed (CR 301.1); its resolution puts a
 * permanent onto the battlefield and does nothing else.
 */
val nihilSpellbomb: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Nihil Spellbomb",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        // CR 301.1: an artifact spell is cast at sorcery speed and targets nothing — the abilities target.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf, AbilityCost.SacrificeSelf),
                    targetSpec = TargetSpec.TargetPlayer(),
                    effect =
                        ResolutionEffect { state, context ->
                            val target =
                                context.targets.singleOrNull() as? Target.Player
                                    ?: error(
                                        "CR 115.1a: Nihil Spellbomb's ability targets a player, " +
                                            "got ${context.targets}",
                                    )
                            exileGraveyard(state, target.id)
                        },
                ),
            )
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    // CR 601.3b: the whole ability is the clause; there is no ordinary effect to run first.
                    effect = ResolutionEffect { state, _ -> state },
                    optionalManaThenDraw =
                        OptionalManaThenDraw(
                            cost = ManaCost.parse(NIHIL_SPELLBOMB_DRAW_COST),
                            drawCount = NIHIL_SPELLBOMB_DRAW,
                        ),
                ),
            )
    }

/**
 * Relic of Progenitus — `{1}` Artifact. "{T}: Target player exiles a card from their graveyard. {1},
 * Exile this artifact: Exile all graveyards. Draw a card."
 *
 * **The first ability's choice belongs to the *targeted player*, not to the Relic's controller**
 * (CR 701.3a): "target player exiles" makes that player perform the action, and a player who performs an
 * action makes its choices. That is [TargetPlayerExilesFromGraveyard], and it is the reason the ability
 * is a clause rather than a [ResolutionEffect] — ADR-004 forbids a resolution effect calling back for a
 * decision, and this one is not even the controller's.
 *
 * **The card in the graveyard is not a target.** Only the player is (CR 115.1a), so an opponent who
 * empties their graveyard in response does *not* make the ability fizzle: it resolves and exiles
 * nothing. Encoding the line as a graveyard-card target would have got both the decider and the fizzle
 * wrong, which is why the clause exists at all.
 *
 * **The second ability exiles the Relic rather than sacrificing it** ([AbilityCost.ExileSelf]), and on
 * this card the distinction is not cosmetic: the ability then exiles *all* graveyards, so a Relic that
 * sacrificed itself would be putting itself into a graveyard it is about to empty. The printed cost
 * removes the question entirely.
 *
 * **"Exile all graveyards" includes the controller's own** ([exileAllGraveyards]) — it names no player,
 * so there is nobody to point it at, and the symmetry is the card's real cost. The draw that follows is
 * what pays the controller for it.
 *
 * The Relic can be tapped for the first ability repeatedly across turns and *then* cashed in for the
 * second; the two abilities share a source but not a cost, and only the first taps it.
 */
val relicOfProgenitus: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Relic of Progenitus",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        // CR 301.1: an artifact spell is cast at sorcery speed and targets nothing.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.TapSelf),
                    targetSpec = TargetSpec.TargetPlayer(),
                    // CR 701.3a: the whole ability is the clause — the *targeted* player chooses.
                    effect = ResolutionEffect { state, _ -> state },
                    targetPlayerExilesFromGraveyard = TargetPlayerExilesFromGraveyard,
                ),
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{1}")),
                            AbilityCost.ExileSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(exileAllGraveyards(state), context.controller, RELIC_OF_PROGENITUS_DRAW)
                        },
                ),
            )
    }
