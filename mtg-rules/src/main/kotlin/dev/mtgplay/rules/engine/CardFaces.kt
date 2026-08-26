package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AlternativeFace
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.FaceKind
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.toPersistentList

/*
 * Two faces on one card (CR 715 — Adventurer Cards, CR 720 — Omen Cards): the seam that lets a cast
 * run against the card's *alternative* characteristics instead of its printed ones.
 *
 * **The whole framework is one substitution.** `mtg-rules` already reaches a card's definition through
 * exactly one function ([spellDefinitionOf]) at every point of the CR 601 pipeline — legality,
 * gathering, the request derivation, and the propose stage that fixes the definition onto the stack
 * entry. CR 715.3b and CR 720.3b say a spell cast as a face "has **only** its alternative
 * characteristics", which is that same function answering with the face instead. Everything downstream
 * follows for free and needed no edit of its own: the CR 111/613 stack seam
 * ([spellCharacteristics]) reads `entry.definition`, so an Adventure on the stack is a sorcery named
 * *Forktail Sweep* to every counter predicate; `isPermanentSpell` reads the same, so a creature card
 * cast as a sorcery resolves off the stack rather than onto the battlefield; the targeting lines, the
 * CR 608.2b fizzle and the resolution fold all read it too.
 *
 * **Nothing here is a second registry key**, which is the design the framework's own recorded diagnosis
 * (AlternateCastings.kt, `W9-G`) named as the alternative and warned would "reach every card in the
 * pool". A [CardRef] stays the card's name in every zone (CR 715.2c, CR 720.2c — an adventurer card is
 * *one* card), so no CR 400.7 zone move has to decide which key an object carries, and
 * `PrintedCharacteristics` grew no second slot that every read would have to consult. The face is
 * reached only from the card's own definition and only while the card is a spell cast as that face
 * (CR 715.4, CR 720.4).
 *
 * **The permission is synthesized, never declared** — the shape ward's trigger and ninjutsu's activated
 * ability already use. A card declares its face once ([SpellDefinition.alternativeFace]); the cost and
 * name a [CastingPermission.Adventure] carries are read off it here, so the two cannot disagree and no
 * card is free to restate the mechanic wrongly.
 */

/**
 * The face [permission] casts a card as (CR 715.3, CR 720.3), or `null` for every other permission and
 * for a normal cast — the single test the rest of the engine asks "was this cast as a face?" through.
 */
internal fun faceKindOf(permission: CastingPermission?): FaceKind? =
    when (permission) {
        is CastingPermission.Adventure -> FaceKind.ADVENTURE
        is CastingPermission.Omen -> FaceKind.OMEN
        else -> null
    }

/**
 * The printed name of the face [permission] casts a card as (CR 201, CR 715.3b, CR 720.3b), or `null`
 * for every other permission and for a normal cast.
 *
 * The public half of [faceKindOf], and the one thing about a face cast that crosses the seat-view
 * boundary: the stack is public (CR 405), so which of a two-faced card's halves is on it is a fact
 * every seat may see, while the face's rules text is static card data an agent already holds.
 */
internal fun faceNameOf(permission: CastingPermission?): String? =
    when (permission) {
        is CastingPermission.Adventure -> permission.faceName
        is CastingPermission.Omen -> permission.faceName
        else -> null
    }

/**
 * The [SpellDefinition] the cast of [card] via [permission] runs against (CR 715.3a, CR 720.3a): the
 * card's **alternative** characteristics when the permission names a face, and its printed ones
 * otherwise.
 *
 * The face-aware [spellDefinitionOf], and the one function every stage of the CR 601 pipeline reaches a
 * definition through — legality, gathering, the pending-request derivation, and the propose stage.
 * Routing them all here is what makes CR 715.3b's *"the spell has only its alternative
 * characteristics"* true by construction rather than by four call sites remembering.
 *
 * Fails loudly when the permission names a face the card does not print: a permission is synthesized by
 * [castingPermissionsOf] from the declared face and can reach this no other way, so a mismatch is an
 * engine defect rather than a rules case (ADR-005).
 */
internal fun castDefinitionOf(
    state: GameState,
    card: CardRef,
    permission: CastingPermission?,
): SpellDefinition {
    val printed = spellDefinitionOf(state, card)
    val kind = faceKindOf(permission) ?: return printed
    val face =
        printed.alternativeFace
            ?: error(
                "CR 715.2: ${card.name} is being cast as a $kind but prints no alternative face; " +
                    "only a face this card declares can be enumerated (ADR-005)",
            )
    require(face.kind == kind) {
        "CR 715.2: ${card.name} prints a ${face.kind} face but is being cast as a $kind"
    }
    return face.definition
}

/**
 * Every alternative way [definition] may be cast (CR 601.2f): the permissions the card **declares**
 * ([SpellDefinition.castingPermissions]) plus the one the engine **synthesizes** from its alternative
 * face, if it prints one (CR 715.3, CR 720.3).
 *
 * The one list the priority-window enumeration walks, so a face cast is offered by exactly the machinery
 * flashback and escape already go through — its own timing gate, its own targeting gate, its own
 * payment enumeration — with the *face's* definition supplying all three ([castDefinitionOf]).
 *
 * A synthesized permission carries the face's printed cost and name and nothing else, because those are
 * the two things that must survive the trip to a remote seat and back; see
 * [CastingPermission.Adventure].
 */
internal fun castingPermissionsOf(definition: SpellDefinition): List<CastingPermission> {
    val face = definition.alternativeFace ?: return definition.castingPermissions
    return definition.castingPermissions + facePermissionOf(face)
}

/**
 * The [CastingPermission] that offers [face] (CR 715.3, CR 720.3), derived from the face's own printed
 * cost and name so the option a seat sees and the definition the cast runs against can never diverge.
 */
private fun facePermissionOf(face: AlternativeFace): CastingPermission {
    val printed = face.definition.characteristics
    // Non-null by AlternativeFace's own construction check (CR 601.2f: a cast needs a cost).
    val cost =
        printed.manaCost
            ?: error("CR 601.2f: the ${face.kind} face \"${printed.name}\" prints no mana cost")
    return when (face.kind) {
        FaceKind.ADVENTURE -> CastingPermission.Adventure(cost, printed.name)
        FaceKind.OMEN -> CastingPermission.Omen(cost, printed.name)
    }
}

/**
 * Marks the exile object [exileObjectId] as being **on an adventure** (CR 715.3d) — the card an
 * Adventure spell of its own has just resolved into exile, which its controller may play from there for
 * as long as it stays.
 *
 * The sibling of [markReboundExile], and simpler for the one reason CR 715.3d and CR 702.88a differ: a
 * rebound exile records the *turn*, because the permission it grants expires at the next upkeep; an
 * adventure's grant has no end to compute, so the marker is a boolean and it disappears with the object
 * when the card is finally played (CR 400.7).
 *
 * Fails loudly on an id that is not in exile: only a resolving Adventure reaches here, and it has just
 * put the card there.
 */
internal fun markAdventureExile(
    state: GameState,
    exileObjectId: ObjectId,
): GameState {
    require(state.sharedZones.exile.any { it.id == exileObjectId }) {
        "CR 715.3d: a card on an adventure must be in exile to be marked, but $exileObjectId is not"
    }
    return state.updateExile { exile ->
        exile
            .map { if (it.id == exileObjectId) it.copy(onAnAdventure = true) else it }
            .toPersistentList()
    }
}
