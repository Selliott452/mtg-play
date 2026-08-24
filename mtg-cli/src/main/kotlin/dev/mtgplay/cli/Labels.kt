package dev.mtgplay.cli

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.layeredCharacteristics

/*
 * Small pure label helpers: how one battlefield permanent, hand card, or target reads as text.
 * Rendering encodes rules facts, so the CR paragraphs the labels reflect are cited where they bite
 * (effective P/T is CR 613 sublayer 7c; effective keywords are CR 613 layer 6).
 */

/**
 * A battlefield permanent's one-line label (CR 110): its name, effective power/toughness for a
 * creature (CR 613 sublayer 7c, via [layeredCharacteristics]) or a type tag otherwise, plus its
 * status tags - tapped, summoning sick (CR 302.6), marked damage (CR 120.3), effective keywords
 * (CR 613 layer 6), and an Aura's attachment (CR 303.4). [obj] must be on the battlefield.
 */
fun permanentLabel(
    state: GameState,
    obj: GameObject,
): String {
    val chars = layeredCharacteristics(state, obj.id)
    val isCreature = chars.power != null && chars.toughness != null
    val printed = state.definitions[obj.card]?.characteristics
    val head =
        if (isCreature) {
            "${obj.card.name} ${chars.power}/${chars.toughness}"
        } else {
            "${obj.card.name} (${typeTag(obj, printed)})"
        }
    val tags = permanentTags(state, obj, isCreature, chars.keywords)
    return if (tags.isEmpty()) head else "$head  [${tags.joinToString(", ")}]"
}

/** The status tags of a battlefield permanent, in a stable render order. */
private fun permanentTags(
    state: GameState,
    obj: GameObject,
    isCreature: Boolean,
    keywords: Set<Keyword>,
): List<String> =
    buildList {
        if (obj.tapped) add("tapped")
        // CR 302.6: summoning sickness only restricts creatures; harmless noise on anything else.
        if (isCreature && obj.summoningSick) add("sick")
        if (obj.damageMarked > 0) add("${obj.damageMarked} dmg")
        keywords.forEach { add(it.name.lowercase().replace('_', ' ')) }
        obj.attachedTo?.let { add("attached to ${attachedName(state, it)}") }
    }

/** The name of the object [id] an Aura is attached to (CR 303.4), with its id for disambiguation. */
private fun attachedName(
    state: GameState,
    id: ObjectId,
): String {
    val target = state.sharedZones.battlefield.firstOrNull { it.id == id }
    return if (target != null) "${target.card.name}#${id.value}" else "#${id.value}"
}

/** A short type tag for a non-creature permanent (CR 205.2): Land, Aura, Enchantment, Artifact, ... */
private fun typeTag(
    obj: GameObject,
    printed: PrintedCharacteristics?,
): String {
    val types = printed?.cardTypes ?: return "?"
    val aura = obj.attachedTo != null || (CardType.ENCHANTMENT in types && isAura(printed))
    return when {
        aura -> "Aura"
        CardType.LAND in types -> "Land"
        CardType.ENCHANTMENT in types -> "Enchantment"
        CardType.ARTIFACT in types -> "Artifact"
        else ->
            types
                .first()
                .name
                .lowercase()
                .replaceFirstChar(Char::uppercase)
    }
}

/** Whether a card is an Aura (CR 303.1a): an enchantment with the Aura subtype. */
private fun isAura(printed: PrintedCharacteristics): Boolean = printed.subtypes.any { it.value == "Aura" }

/**
 * A hand card's one-line label: its name, printed mana cost (CR 202), and a compact type line. Used
 * only when rendering the viewer's own hand - never an opponent's (hidden information, [MatchView]).
 */
fun handCardLabel(
    state: GameState,
    obj: GameObject,
): String {
    val printed = state.definitions[obj.card]?.characteristics
    val cost =
        printed
            ?.manaCost
            ?.render()
            ?.let { " $it" }
            .orEmpty()
    val types =
        printed
            ?.cardTypes
            ?.joinToString(" ") { it.name.lowercase() }
            ?.let { " - $it" }
            .orEmpty()
    return "${obj.card.name}$cost$types"
}

/**
 * A target's label (CR 115.1): a player by name, a battlefield permanent by name and id, or a spell on
 * the stack by name and id (CR 111.1). A spell whose stack entry has already gone is still labelled,
 * opaquely — a stale target is exactly what the CR 608.2b re-check is about to fizzle, and hiding it
 * would hide the reason.
 */
fun targetLabel(
    view: MatchView,
    target: Target,
): String =
    when (target) {
        is Target.Player -> view.nameOf(target.id)
        is Target.Permanent -> {
            val obj =
                view.state.sharedZones.battlefield
                    .firstOrNull { it.id == target.id }
            if (obj != null) "${obj.card.name}#${target.id.value}" else "permanent#${target.id.value}"
        }
        is Target.SpellOnStack -> {
            val spell =
                view.state.sharedZones.stack
                    .filterIsInstance<StackEntry.Spell>()
                    .firstOrNull { it.obj.id == target.id }
            if (spell != null) "${spell.obj.card.name}#${target.id.value}" else "spell#${target.id.value}"
        }
    }
