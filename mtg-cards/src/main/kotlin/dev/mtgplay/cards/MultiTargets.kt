package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.exileCardFromGraveyard
import dev.mtgplay.rules.effect.returnToOwnersHand
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **multi-target** cards (`FW-MULTITGT`, docs/design/multi-target.md): the spells and
 * abilities whose targeting line names more than one object, which until this packet `TargetSpec` could
 * not express at all.
 *
 * Both cards here print "up to two target … cards", which is [TargetCount.UpTo]`(2)` over the
 * [TargetSpec.CardInGraveyard] noun `FW-ZONETGT` already shipped — the whole point of splitting a spec
 * into a noun and a count is that these two needed no new noun. `FW-ZONETGT` listed both as blocked on
 * exactly this (docs/design/graveyard-targeting.md §6), and its list is now two shorter.
 *
 * **Two of that list's four "up to two" cards are still absent**, each blocked on a framework this
 * packet does not own; an approximation of either would be a plausible-looking wrong card (PLAN.md §7):
 * - **Rooftop Percher** is this exact ability plus **changeling** (CR 702.73) — "this card is every
 *   creature type", which nothing in the subtype model expresses and which is not cosmetic in a gauntlet
 *   holding tribal effects. Its targeting half is complete and unused.
 * - **Call Damage Control** needs `FW-MODAL` first: "choose up to two" *modes*, each carrying its own
 *   instance of the word "target". That is a second axis this packet deliberately does not model — one
 *   targeting line with a count, not a list of targeting lines (docs/design/multi-target.md §7).
 */

/** The creature cards Blood Fountain's activated ability may return (CR 115.1). */
private const val BLOOD_FOUNTAIN_TARGETS: Int = 2

/** The cards Faerie Macabre's activated ability may exile (CR 115.1). */
private const val FAERIE_MACABRE_TARGETS: Int = 2

/**
 * Faerie Macabre — `{1}{B}{B}` Creature — Faerie Rogue 2/2. "Flying. Discard this card: Exile up to two
 * target cards from graveyards."
 *
 * The pool's first multi-target object, and it lands on the shape that stresses the framework hardest:
 * an ability that functions **from the hand** (CR 113.6c, [AbilityZoneScope.Hand]) whose whole cost is
 * [AbilityCost.DiscardSelf] — free, instant-speed graveyard hate that never touches the battlefield.
 * The pieces below the targeting are all published: Ash Barrens' basic landcycling established the
 * hand-scoped ability with a discard-self cost, and the exile is this packet's one new primitive
 * ([exileCardFromGraveyard], CR 701.3a).
 *
 * **"Up to two" is a real choice, and all three answers matter in play.** Exiling nothing is legal and
 * is sometimes correct (holding priority); exiling one leaves the other card for a second Faerie
 * Macabre; exiling two is the usual line. [TargetCount.UpTo] is what makes the ability *activatable*
 * with two empty graveyards at all — a minimum of zero passes the CR 601.2c castability gate — and what
 * stops the resulting target-less ability from being read as a CR 608.2b fizzle.
 *
 * **The Faerie is never a legal target of its own ability.** Targets are chosen at CR 601.2b, before the
 * CR 601.2h cost puts the card into the graveyard, so the enumeration runs while the card is still in
 * hand. That falls out of the pipeline's ordering with no special case, which is the same reason a
 * counter is not a legal target for itself.
 *
 * **"From graveyards" is [GraveyardScope.ANY] and "cards" is [GraveyardCardRestriction.ANY_CARD]** —
 * both graveyards, no type restriction, which is the widest targeting line in the pool. It is also the
 * first line whose two chosen targets can sit in *different* players' graveyards, which is why
 * CR 601.2c's same-object rule has to be a check on object identity rather than on position.
 */
val faerieMacabre: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Faerie Macabre",
                manaCost = ManaCost.parse("{1}{B}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Faerie"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        // CR 302.1: the creature spell itself is cast at sorcery speed and targets nothing — the
        // hand-scoped *ability* is what targets, and it is usable without ever casting the card.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 113.6c: "Discard this card:" is the whole cost — no mana, no tap.
                    cost = persistentListOf(AbilityCost.DiscardSelf),
                    zoneScope = AbilityZoneScope.Hand,
                    targetSpec =
                        TargetSpec.CardInGraveyard(
                            restriction = GraveyardCardRestriction.ANY_CARD,
                            scope = GraveyardScope.ANY,
                            count = TargetCount.UpTo(FAERIE_MACABRE_TARGETS),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            exileTargetedGraveyardCards(state, context.targets, "Faerie Macabre")
                        },
                ),
            )
    }

/**
 * Blood Fountain — `{B}` Artifact. "When this artifact enters, create a Blood token. `{3}{B}`, `{T}`,
 * Sacrifice this artifact: Return up to two target creature cards from your graveyard to your hand."
 *
 * The multi-target family's other half: an "up to two" line on a **battlefield** activated ability with
 * a composite cost, where Faerie Macabre's is a free hand-scoped one. Everything but the cardinality
 * already existed — [bloodToken] since P6.2c, the `{3}{B}` + `{T}` + sacrifice-self cost shape since
 * P6.2a, [returnToOwnersHand] since P4 — which is precisely what
 * docs/design/graveyard-targeting.md §6 recorded when it listed this card as blocked on nothing else.
 *
 * **The Fountain is not in the graveyard when its targets are chosen.** The `{T}` and sacrifice are paid
 * at CR 602.2b *after* the CR 601.2c target choice, so the artifact cannot return itself even once it is
 * a card in the graveyard — and it is not a creature card either way. Two rules would each stop it; the
 * ordering is the one that does.
 *
 * **[GraveyardScope.YOURS] and [GraveyardCardRestriction.CREATURE]**, both narrower than Faerie
 * Macabre's: "your graveyard" makes the enumeration decider-relative and "creature cards" excludes the
 * lands and spells beside them. The restriction is a new enum member rather than a reuse of
 * `CREATURE_OR_LAND`, because a land card in the graveyard is a legal Pulse of Murasa target and an
 * illegal one here.
 */
val bloodFountain: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Blood Fountain",
                manaCost = ManaCost.parse("{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        // CR 603.2: "When this artifact enters, create a Blood token." The token carries its own
        // activated ability; nothing about it is this card's concern (CR 707.2).
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect = ResolutionEffect { state, context -> createToken(state, context.controller, bloodToken) },
                ),
            )

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{3}{B}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    targetSpec =
                        TargetSpec.CardInGraveyard(
                            restriction = GraveyardCardRestriction.CREATURE,
                            scope = GraveyardScope.YOURS,
                            count = TargetCount.UpTo(BLOOD_FOUNTAIN_TARGETS),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            returnTargetedGraveyardCards(state, context.targets, "Blood Fountain")
                        },
                ),
            )
    }

/**
 * Exiles every graveyard card an effect was told to act on (CR 701.3a), in the order they were chosen.
 *
 * **An empty list is a correct input, not a defect**, and that is the difference from `FW-ZONETGT`'s
 * single-target helper, which fails loudly on one. An "up to two" line may legitimately name no target —
 * declined by its controller, or with nothing legal to name — and CR 608.2b lets the object resolve
 * anyway. What still fails loudly is a target of the *wrong kind*: the re-check has already run, so
 * anything but a [Target.CardInGraveyard] here is an engine defect (ADR-005).
 */
private fun exileTargetedGraveyardCards(
    state: GameState,
    targets: List<Target>,
    cardName: String,
): GameState = targetedGraveyardCards(targets, cardName).fold(state, ::exileCardFromGraveyard)

/** Returns every targeted graveyard card to its owner's hand (CR 400.7); see [exileTargetedGraveyardCards]. */
private fun returnTargetedGraveyardCards(
    state: GameState,
    targets: List<Target>,
    cardName: String,
): GameState = targetedGraveyardCards(targets, cardName).fold(state, ::returnToOwnersHand)

/** The graveyard cards among [targets] (CR 115.1, CR 404), failing loudly on any other target kind. */
private fun targetedGraveyardCards(
    targets: List<Target>,
    cardName: String,
): List<ObjectId> =
    targets.map { target ->
        (target as? Target.CardInGraveyard)?.id
            ?: error("CR 115.1: $cardName targets only cards in graveyards, got $target")
    }
