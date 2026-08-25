package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Gingerbread Cabin and the Food token it makes — the Elves list's one utility land (docs/decklists.md),
 * and the first card to exercise two primitives at once: a **conditional** CR 614.1c enters-tapped
 * clause (`P-ETBTAPPED`) and an "entered untapped" trigger condition (`P-TRIGCOND`).
 *
 * It is also the first real card to reach the play-land enters-the-battlefield path at all. That path
 * silently dropped CR 603.6a triggers until the fix the gauntlet triage records as **T18**; the card
 * and the fix landed together, which is why the acceptance coverage below asserts the token actually
 * appears rather than merely that the land arrives untapped.
 */

/** The Forests Gingerbread Cabin must see to enter untapped — "three or more **other** Forests". */
const val GINGERBREAD_CABIN_FORESTS: Int = 3

/** The life the Food token's sacrifice ability gains (CR 120.1). */
const val FOOD_TOKEN_LIFE: Int = 3

/**
 * The Food token (CR 111.4) Gingerbread Cabin creates: a colorless artifact token of subtype Food with
 * "{2}, {T}, Sacrifice this token: You gain 3 life."
 *
 * A **non-mana** activated ability on a token, which the [TokenDefinition.activatedAbilities] field has
 * carried since P6.2c — [bloodToken]'s "{1}, {T}, Discard a card, Sacrifice this token: Draw a card" is
 * the precedent, and a strictly larger composite cost than this one. The engine reads it through the
 * same `definitions[card].activatedAbilities` path a real card's ability uses (CR 113.6), so nothing
 * about the token being a token is special-cased.
 *
 * The cost is [AbilityCost.Mana]`({2})` + [AbilityCost.TapSelf] + [AbilityCost.SacrificeSelf] in
 * printed order (CR 602.1). Note the CR 602.2a interaction the triage records as **T17**: a `{T}`
 * component reserves its own source from funding the `{2}`, which is correct and costs nothing here
 * because a Food token is no mana source to begin with.
 *
 * Being an artifact rather than a creature, the token is not summoning sick for the purposes of its
 * `{T}` cost (CR 302.6 applies to creatures), so it may be sacrificed the turn it arrives.
 */
val foodToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Food",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Food")),
                powerToughness = null,
            ),
        activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, FOOD_TOKEN_LIFE)
                        },
                ),
            ),
    )

/**
 * Gingerbread Cabin — `Land — Forest`. "({T}: Add {G}.) This land enters tapped unless you control
 * three or more other Forests. When this land enters untapped, create a Food token."
 *
 * **The conditional enters-tapped clause** ([EntersTapped.UnlessYouControl], CR 614.1c) is what this
 * card exists to encode. It was declared inexpressible while `CardDefinition.entersTapped` was a
 * `Boolean` — the property's own KDoc said such a card "stays unencoded rather than being approximated
 * by `true` or `false`" — so the type was widened rather than the card approximated.
 *
 * "Three or more **other** Forests" needs no explicit self-exclusion. A CR 614.1c replacement is
 * evaluated as the permanent enters, when it has not yet joined the battlefield, so the count is over
 * the other permanents by construction (see `entersTappedNow`). Being played on turn one it is
 * therefore always tapped; the untapped mode is a late-game top-deck, which is exactly what the Elves
 * list wants from its single copy.
 *
 * **The trigger is conditioned, not gated inside its effect**
 * ([TriggerCondition.EnteredBattlefieldUntappedSelf]). A land that entered tapped fires nothing at all,
 * rather than firing an ability that resolves doing nothing — a real difference, because the second
 * would use the stack and could be responded to. Tapping the land in response to the trigger does not
 * stop the token either: the tapped status is part of the entering event, fixed once, not an
 * intervening-if re-checked on resolution (CR 603.4).
 *
 * The parenthesised "{T}: Add {G}" is reminder text for the CR 305.6 ability its Forest land type
 * grants; per the P2.2 architect decision recorded in BasicLands.kt it is authored explicitly rather
 * than derived from the subtype. The Forest subtype is carried too, because the card counts Forests and
 * two Cabins count each other.
 */
val gingerbreadCabin: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gingerbread Cabin",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(Subtype("Forest")),
                powerToughness = null,
            )
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
        override val entersTapped =
            EntersTapped.UnlessYouControl(
                filter = PermanentFilter(Subtype("Forest"), controlledByYou = true),
                atLeast = GINGERBREAD_CABIN_FORESTS,
            )
        override val triggeredAbilities: PersistentList<TriggeredAbility> =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldUntappedSelf,
                    effect = ResolutionEffect { state, context -> createToken(state, context.controller, foodToken) },
                ),
            )
    }
