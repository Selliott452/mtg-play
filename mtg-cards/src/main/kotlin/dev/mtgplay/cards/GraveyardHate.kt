package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.exileGraveyard
import dev.mtgplay.rules.effect.returnRandomCardFromGraveyardToHand
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's graveyard-interaction lands: Bojuka Bog, which exiles a graveyard as it enters, and Haunted
 * Fengraf, which sacrifices itself to buy a creature card back at random.
 *
 * Two engine primitives, one each. Bojuka Bog wanted `exileGraveyard` — a **whole-zone** exile, distinct
 * from the per-card `exileCardFromGraveyard` the targeted removal already had, because it names a *player*
 * and so has no per-card target and no per-card fizzle. Haunted Fengraf wanted
 * `returnRandomCardFromGraveyardToHand`, and it wanted it in `mtg-rules`: ADR-006 routes every random
 * outcome through the match-owned PRNG, and a card definition reaching into `state.rng` itself would put a
 * seeded draw in the wrong module and make the generator's advance depend on card text. Both primitives are
 * published (ADR-003), so the definitions below are declarations and nothing else.
 *
 * **Bojuka Bog is the first encoded land with an enters-the-battlefield trigger**, which makes it the first
 * card that can observe the gauntlet's **T18** defect: `executePlayLand` used to narrate the entry and skip
 * the triggers, silently, because a trigger that never fires leaves nothing behind. That path now routes
 * through the same `announceBattlefieldEntry` a resolving permanent uses (CR 603.6a applies to a *played*
 * land exactly as it does to a cast one), so playing a Bog fires its trigger; `GraveyardHateSpec` pins it.
 *
 * **Their two siblings in the same triage row have since landed** (GraveyardArtifacts.kt, `W8-D`), and each
 * of the three blockers this comment used to record was closed exactly where it was diagnosed. Nihil
 * Spellbomb's dies trigger — "you may pay {B}. If you do, draw a card" — is an optional *mana* cost inside a
 * resolution, which `OptionalCostMode` (a discard or a land sacrifice) genuinely could not express; it is
 * now its own clause, [dev.mtgplay.core.definition.OptionalManaThenDraw]. Relic of Progenitus needed a
 * decision made by a **non-controller**, now
 * [dev.mtgplay.core.definition.TargetPlayerExilesFromGraveyard], and an `AbilityCost` member for
 * "{1}, Exile this artifact:", now [dev.mtgplay.core.definition.AbilityCost.ExileSelf]. The exile
 * primitives below needed no change at all: both cards compose [exileGraveyard] and its siblings unmodified.
 */

/** What Haunted Fengraf's sacrifice ability costs in generic mana (CR 602.1). */
private const val HAUNTED_FENGRAF_ACTIVATION_COST: String = "{3}"

/**
 * Bojuka Bog — Land. "This land enters tapped. When this land enters, exile target player's graveyard.
 * {T}: Add {B}."
 *
 * A land, so it is **played** and never cast (CR 305.1, CR 116.2a) — a plain [CardDefinition], not a
 * [dev.mtgplay.core.definition.SpellDefinition] — and its whole rules text is three clauses the published
 * vocabulary already had a home for: the CR 614.1c self-replacement [EntersTapped.Always], one intrinsic
 * [ManaAbility], and one [TriggeredAbility] whose [TargetSpec] is `TargetPlayer`.
 *
 * **The trigger targets, and it may target its own controller.** "Target player" (CR 115.1a) offers both
 * seats, which is the printed rule rather than an over-generous enumeration: a Bog played into an empty
 * board legally exiles its controller's own graveyard, and forcing the opponent would be an engine-invented
 * restriction. A player stops being a legal target only by leaving the game, which in a two-player game *is*
 * the game ending (CR 104.2a), so this trigger can never reach the CR 608.2b fizzle.
 *
 * **Enters tapped and the trigger both fire** — they are not alternatives. CR 614.1c modifies the *entering
 * event* (so the land arrives tapped rather than being tapped afterwards), while CR 603.6a fires off the
 * completed entry; the land is on the battlefield, tapped, before the ability goes on the stack. The
 * graveyard is a public zone (CR 400.2), so nothing about the exile needs per-seat filtering.
 */
val bojukaBog: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Bojuka Bog",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))
        override val entersTapped = EntersTapped.Always
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec = TargetSpec.TargetPlayer(),
                    effect =
                        ResolutionEffect { state, context ->
                            val target =
                                context.targets.single() as? Target.Player
                                    ?: error(
                                        "CR 115.1a: Bojuka Bog's target is a player, got ${context.targets}",
                                    )
                            exileGraveyard(state, target.id)
                        },
                ),
            )
    }

/**
 * Haunted Fengraf — Land. "{T}: Add {C}. {3}, {T}, Sacrifice this land: Return a creature card at random
 * from your graveyard to your hand."
 *
 * **Two costs on one source, and the reason this card waited.** The sacrifice ability's cost is
 * `{3}` + `{T}` + sacrifice-this — three components on a permanent that must still be untapped to pay the
 * `{T}`, which is the composite shape `FW-MANACOST` built the activation pipeline for and which the triage
 * records as this card's only remaining blocker. The mana half is a plain [ManaAbility] (CR 605.1a) and is
 * a *different* ability, not a mode of the other: paying `{T}` for one spends the source for both, which is
 * a consequence of the cost rather than anything either ability declares.
 *
 * **"At random" is not a decision** (CR 104.3, ADR-005). Nothing is enumerated and no seat is asked; the
 * engine picks. The pick lives in `returnRandomCardFromGraveyardToHand`, a `mtg-rules` primitive that draws
 * from the match-owned PRNG and returns the successor generator on the state (ADR-006), so a replay of the
 * same seed and decision log returns the same card. This definition names the restriction and nothing else
 * — a card definition that drew from `state.rng` itself would put seeded randomness in `mtg-cards` and make
 * the generator's advance a property of card text rather than of the engine.
 *
 * Both cost components are paid on activation (CR 602.2b), so the land is already in the graveyard when the
 * ability resolves. That is observable: the Fengraf is **not** a creature card, so it never becomes its own
 * return candidate, but a creature that died earlier this turn is. An empty (or creatureless) graveyard
 * returns nothing and — deliberately — draws nothing from the PRNG, so it cannot desynchronise a replay
 * against a game where the ability found a card.
 */
val hauntedFengraf: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Haunted Fengraf",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        override val activatedAbilities: PersistentList<ActivatedAbility> =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse(HAUNTED_FENGRAF_ACTIVATION_COST)),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            returnRandomCardFromGraveyardToHand(
                                state,
                                context.controller,
                                GraveyardCardRestriction.CREATURE,
                            )
                        },
                ),
            )
    }
