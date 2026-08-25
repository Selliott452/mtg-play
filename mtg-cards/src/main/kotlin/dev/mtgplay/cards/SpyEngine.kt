package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.millUntil
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The Spy Combo engine's namesake (docs/decklists.md, "Spy Combo") — the card the whole deck is built
 * to break.
 *
 * Only [balustradeSpy] is here. Its combo partners are elsewhere or absent: Undercity Informer is not
 * in the gauntlet's list, and Dread Return (the reanimation half that turns a full graveyard into a
 * win) needs a flashback cost paid by *sacrificing creatures*, which no `CastingPermission` expresses.
 * Encoding the Spy alone is still worth doing: it is the deck's only card that mills a whole library
 * in one resolution, and that is the state every other piece of the deck reads.
 */

/**
 * Balustrade Spy — `{3}{B}` Creature — Vampire Rogue, 2/3, Flying. "When this creature enters, target
 * player reveals cards from the top of their library until they reveal a land card, then puts those
 * cards into their graveyard."
 *
 * **The card is a modest looter; the *deck* is what makes it a combo.** Pointed at an ordinary
 * seventeen-land deck it mills two or three cards. Pointed at a deck containing **no land at all** —
 * which is exactly what Spy Combo builds, on Lotus Petal and Tinder Wall for mana — the "until they
 * reveal a land card" condition is never met, so the *entire library* goes to the graveyard in one
 * resolution. That is not a special case in the engine and gets no special case here: [millUntil]
 * stops when the library runs out, and CR 704.5b's "loses the game" is a consequence of *drawing*
 * from an empty library, not of having one, so the Spy's controller does not lose until their next
 * draw step. Encoding the run as "up to and including the first land, or the whole library" is the
 * single behaviour that serves both readings.
 *
 * **It targets a player, so it can be pointed either way**, and both directions are real plays: at
 * yourself to assemble the combo, or at the opponent as a (bad) mill spell. `TargetSpec.TargetPlayer`
 * enumerates both seats, which is the correct option list — narrowing it to the controller would
 * delete a legal play (ADR-005).
 *
 * The trigger targets, so its target is chosen as it is put on the stack (CR 603.3d) and re-checked at
 * CR 608.2b. A player only stops being a legal target by leaving the game, so this trigger's fizzle
 * stays unreachable in a two-player game — unlike a permanent-targeting one.
 */
val balustradeSpy: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Balustrade Spy",
                manaCost = ManaCost.parse("{3}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Vampire"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 3),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        // CR 302.1/601.3a: the creature spell targets nothing — the *ability* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // CR 115.1a: either seat, chosen at CR 603.3d.
                    targetSpec = TargetSpec.TargetPlayer,
                    effect =
                        ResolutionEffect { state, context ->
                            millUntil(state, targetedPlayer(context.targets, "Balustrade Spy")) { card ->
                                isLandCard(state, card)
                            }
                        },
                ),
            )
    }

/**
 * Whether the card printed as [card] is a land (CR 305.1) — the stop condition of Balustrade Spy's
 * reveal, read from the printed type line.
 *
 * An **unregistered** card is not a land, and the direction of that default is deliberate: it makes an
 * undefined card keep the reveal going rather than stop it, so a fixture with an incomplete definition
 * map mills further than it should instead of silently stopping at the first unknown card and looking
 * like a correct short run. A modal-double-faced land is outside the pool (docs/decklists.md).
 */
private fun isLandCard(
    state: GameState,
    card: CardRef,
): Boolean {
    val cardTypes = state.definitions[card]?.characteristics?.cardTypes ?: return false
    return CardType.LAND in cardTypes
}

/**
 * The single player a "target player" ability chose (CR 115.1a). Fails loudly on any other target
 * shape: the CR 603.3d choice and the CR 608.2b re-check have both already run by resolution time, so
 * a non-player target here is an engine defect rather than a rules case (ADR-005).
 */
private fun targetedPlayer(
    targets: List<Target>,
    cardName: String,
) = (targets.singleOrNull() as? Target.Player)?.id
    ?: error("CR 115.1a: $cardName's ability targets exactly one player, got $targets")
