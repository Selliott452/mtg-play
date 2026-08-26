package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/*
 * The CR 613 layer-4 half of an object's characteristics, computed **without walking static abilities**
 * (`FW-TYPECHANGE`). Two functions, one reason, and the reason is a cycle rather than a speed-up.
 *
 * A conditional static ability's "as long as you control an artifact" (CR 604.3, `FW-CONDSTATIC`) is
 * evaluated inside [staticEffectsOn], and it answers by *counting permanents by card type*. Route that
 * count through the full [layeredCharacteristics] walk and the walk calls the condition which calls the
 * walk: Goblin Tomb Raider's haste sends the engine into a StackOverflowError on any board it is
 * conditioned by. That is not an implementation accident — it is CR 613.8's dependency question arriving
 * as a stack trace, and the honest answer is not to memoise around it but to observe that **layer 4 has
 * exactly one generator**.
 *
 * A type change in this pool can only come from the resolution of a spell or ability (CR 611.2): a
 * [dev.mtgplay.core.definition.StaticContinuousEffect] carries no card types, no subtypes and no set-P/T
 * and deliberately keeps none (see [timedEffectsOn]). So printed types unioned with the *timed* store's
 * layer-4 additions **is** the complete layer-4 answer, identical to what the full walk produces, and it
 * reaches it without ever asking a static ability whether it is active. A static ability's condition may
 * therefore read the type line, and the type line does not read it back.
 *
 * The day a static ability *does* change types, this shortcut becomes a real dependency (CR 613.8) and
 * must be replaced by the ordering rule rather than extended — the cycle it is standing in for is
 * genuine, and the reason there is no wrong answer today is that one side of it is empty.
 */

/**
 * The in-game card types of the battlefield object [id] (CR 205.2, CR 613.1d): printed types unioned
 * with every layer-4 addition from a running timed effect. Empty for an object with no definition.
 */
internal fun layeredCardTypes(
    state: GameState,
    id: ObjectId,
): PersistentSet<CardType> {
    val obj = state.battlefieldObject(id)
    val printed = state.definitions[obj.card]?.characteristics ?: return persistentSetOf()
    return state.timedEffects
        .filter { it.affected == id }
        .fold(printed.cardTypes) { acc, effect -> acc.addingAll(effect.modification.addedCardTypes) }
}

/**
 * The in-game subtypes of the battlefield object [id] (CR 205.3, CR 613.1d): printed subtypes unioned
 * with every layer-4 addition from a running timed effect. Empty for an object with no definition.
 *
 * **Not the whole subtype answer** — CR 702.73a changeling is a layer-6 *ability* and cannot be a word in
 * this set. [hasSubtype] is the seam that joins the two, and it is what every rule should ask.
 */
internal fun layeredSubtypes(
    state: GameState,
    id: ObjectId,
): PersistentSet<Subtype> {
    val obj = state.battlefieldObject(id)
    val printed = state.definitions[obj.card]?.characteristics ?: return persistentSetOf()
    return state.timedEffects
        .filter { it.affected == id }
        .fold(printed.subtypes) { acc, effect -> acc.addingAll(effect.modification.addedSubtypes) }
}

/**
 * The in-game card types of the battlefield object [id] (CR 205.2, CR 613 layer 4): its printed types
 * unioned with every active type-changing addition, via [layeredCharacteristics]. An object with no
 * definition has none.
 *
 * The fifth `effective*` seam, added by `FW-TYPECHANGE`, and it exists for the reason every other one
 * does: several rules must read the *same* type line or they drift. A Kenku-Artificer'd Sky Skiff is a
 * creature for the attacker enumeration, for "target creature", for a sacrifice cost's filter and for
 * a permanent count, and each of those used to answer from the printed characteristics on the recorded
 * grounds that no type-changing effect existed. That is no longer true, and a site left reading printed
 * would not crash — it would quietly refuse a legal attack (ADR-005 in the direction that shrinks the
 * action space).
 *
 * **Battlefield only, and that is CR 613's own scope rather than a limitation.** CR 613 applies to
 * objects on the battlefield; a card in a hand, library or graveyard has its printed types and nothing
 * else, so a read in a hidden zone must stay printed and is not a missed reroute. `isLand()` on a hand
 * card and `matches` on a graveyard card are the two such sites, and each says so where it is.
 *
 * It delegates to [layeredCardTypes] rather than to the full [layeredCharacteristics] walk, and the two
 * give the same answer by construction — layer 4 has one generator, so the walk's `cardTypes` field is
 * exactly what that function computes. The reason it must be the narrower call is a **cycle**: a
 * conditional static ability's "as long as you control an artifact" counts permanents by card type from
 * inside the walk, so a type read that re-enters the walk never terminates. The full argument is on
 * [layeredCardTypes].
 */
internal fun effectiveCardTypes(
    state: GameState,
    id: ObjectId,
): PersistentSet<CardType> = layeredCardTypes(state, id)

/**
 * Whether the battlefield object [obj] is a creature right now (CR 302.1) — its **in-game** card types
 * include creature, printed types plus any CR 613 layer-4 addition ([effectiveCardTypes]). An object
 * with no definition is inert and not a creature.
 *
 * It read the printed type line until `FW-TYPECHANGE`, with a KDoc promising this reroute; the promise
 * is now kept. The observable difference is Kenku Artificer's target attacking, blocking, and dying to
 * the CR 704.5f/g state-based actions like any other creature.
 */
internal fun isCreature(
    state: GameState,
    obj: GameObject,
): Boolean = CardType.CREATURE in effectiveCardTypes(state, obj.id)
