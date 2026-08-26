package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Prototype (CR 702.160, CR 718) — the **base characteristics** seam.
 *
 * CR 718.2: a prototype card carries a second set of mana cost, power and toughness in its inset frame,
 * and CR 718.2a makes their existence and values part of the object's *copiable values*. CR 718.3b then
 * says a prototyped spell — and the permanent it becomes — has **only** that alternative set.
 *
 * **That is what makes prototype cheap, and it is the opposite of what the triage predicted.**
 * docs/gauntlet-deferred-ten.md filed the card as blocked on "a CR 613 layer 1/7b effect keyed to how
 * the spell was cast", which would have made it wait on the layer system growing a type/P-T slot. A
 * copiable value is not a layer effect at all: nothing is *applied* to a prototyped object, it simply
 * starts from a different base. So the whole of the rules change is this file plus three call sites that
 * stop reading `definitions[card].characteristics` directly — no new layer, no new continuous effect, no
 * dependency on the layer packet running beside this one.
 *
 * Three reads had to move onto [baseCharacteristics]:
 * - [layeredCharacteristics]'s base (CR 613.1: the layer walk starts from the object's characteristics
 *   as defined by the card, which for a prototyped permanent is the alternative set);
 * - [colorsOfTarget]'s battlefield arm (CR 718.3b's colour half — a prototyped Golem is green);
 * - [spellCharacteristics], the CR 111/613 stack seam, whose own KDoc reserved exactly this: *"When the
 *   first [effect that changes a spell's characteristics] arrives, one function body changes and every
 *   counter predicate follows."* Prototype is that first client, and the promise held — the counter
 *   predicates, the mana-value read and the cast-trigger colour filter all became prototype-aware with
 *   no edit of their own.
 */

/**
 * The prototype ability of [definition] (CR 702.160a), or `null` for a card without one. A card carries
 * at most one; two would be an ill-formed definition and fail loudly here rather than silently picking
 * the first, in the shape [madnessPermissionOf] already set for madness.
 */
internal fun prototypeOf(definition: CardDefinition?): CastingPermission.Prototype? =
    (definition as? SpellDefinition)
        ?.castingPermissions
        ?.filterIsInstance<CastingPermission.Prototype>()
        ?.let { permissions ->
            require(permissions.size <= 1) {
                "CR 702.160a: a card has at most one prototype ability, but " +
                    "${definition.characteristics.name} declares ${permissions.size}"
            }
            permissions.firstOrNull()
        }

/**
 * [printed] with the alternative mana cost, power and toughness of [prototype] substituted in
 * (CR 718.3b) — the *prototyped* characteristics of an object cast that way.
 *
 * Everything else is untouched, which is CR 702.160a's own restriction: the card keeps its name, its
 * supertypes, its card types, its subtypes, its keywords, its evasions and its protections. The colour
 * change (CR 718.3b's second sentence) needs no code, because
 * [dev.mtgplay.core.card.PrintedCharacteristics.colors] derives colour from the mana cost (CR 202.2) —
 * replacing the cost is what makes a colourless artifact creature green, and the mana value
 * (CR 202.3) follows from the same substitution.
 *
 * Fails loudly on a prototype card with no printed power/toughness box: prototype appears only on
 * creature cards (CR 718.1), and a non-creature declaring one is a card-definition defect rather than a
 * rules case.
 */
internal fun prototypedCharacteristics(
    printed: PrintedCharacteristics,
    prototype: CastingPermission.Prototype,
): PrintedCharacteristics {
    val box =
        printed.powerToughness
            ?: error(
                "CR 718.1: prototype appears on a creature card, but \"${printed.name}\" has no printed " +
                    "power/toughness box",
            )
    return printed.copy(
        manaCost = prototype.cost,
        powerToughness = box.copy(power = prototype.power, toughness = prototype.toughness),
    )
}

/**
 * The **base** characteristics of the object [obj] (CR 109.3, CR 613.1) — what the CR 613 layer walk
 * starts from and what every un-layered read of a permanent's printed values should ask for. The card's
 * printed characteristics for an ordinary object, and the *alternative* set (CR 718.3b) for one marked
 * [dev.mtgplay.core.state.GameObject.prototyped]. `null` for a card with no definition, which is inert.
 *
 * **A function rather than a property on the object, because the values live on the card.** The object
 * carries only the one-bit fact that it was cast prototyped (CR 400.7 — the permanent is a different
 * object from the spell); the alternative values themselves are printed on the card and are reached
 * through the definition registry, so they cannot drift out of step with the definition the way a copy
 * stored on the object could.
 *
 * A prototype **marker on an object whose card declares no prototype ability** is an engine defect
 * rather than a rules case — nothing but a prototyped cast can set the flag — so it fails loudly
 * instead of quietly answering with the printed values.
 */
internal fun baseCharacteristics(
    state: GameState,
    obj: GameObject,
): PrintedCharacteristics? {
    val definition = state.definitions[obj.card] ?: return null
    val printed = definition.characteristics
    return if (!obj.prototyped) {
        printed
    } else {
        val prototype =
            prototypeOf(definition)
                ?: error(
                    "CR 718.3b: object ${obj.id.value} is marked prototyped but \"${printed.name}\" declares " +
                        "no prototype ability; only a prototyped cast can set the marker",
                )
        prototypedCharacteristics(printed, prototype)
    }
}
