package dev.mtgplay.core.definition

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype

/**
 * The **second set of characteristics** printed in the inset frame of a two-faced card (CR 715.2,
 * CR 720.2) — an adventurer card's Adventure and an omen card's Omen. Additive, flagged core
 * (`W10-B`). Fang Dragon's *Forktail Sweep*, Sagu Wildling's *Sagu Wilds*.
 *
 * **Not prototype with a different label, and that distinction is the whole reason this type exists.**
 * CR 718.2a's prototyped set differs from the printed one only in mana cost, colour and size; it keeps
 * the name, the card types and every ability, so it fits inside a [CastingPermission] as three values
 * ([CastingPermission.Prototype]). A face differs in **name** (CR 715.5 — a player naming a card may
 * name either), in **card type** (a Creature card whose face is a Sorcery), and in **rules text**: the
 * two halves resolve differently and target differently. `SpellDefinition.resolution` is one effect and
 * `targetSpec` one spec, so an Adventure's own instructions have nowhere to live on the card's
 * definition — which is why [definition] is a whole second [SpellDefinition] rather than a bag of
 * overridden fields.
 *
 * **Nor is it a [SpellMode].** A mode is chosen *within* one cast, at CR 601.2b, after the spell is
 * already on the stack at one cost; a face is chosen *before* the cast is enumerated (CR 715.3,
 * CR 720.3), at a different cost, and produces a spell of a different card type. The two are offered
 * to a seat as separate priority options, which is what ADR-005 requires of them.
 *
 * **One card, one registry key.** [dev.mtgplay.core.identity.CardRef] stays the *card's* name for both
 * halves and in every zone (CR 715.2c, CR 720.2c: an adventurer card is one card), so nothing about
 * CR 400.7 zone moves changes and no second registration exists to disagree with the first. The face is
 * reached only through the card's own definition, and only while the card is a spell cast as that face
 * (CR 715.4, CR 720.4).
 *
 * @property kind which rule the inset frame follows (CR 715 or CR 720) — the two differ in exactly one
 *   clause, what happens to the card as the face's spell resolves.
 * @property definition the face's own characteristics, timing, targeting and resolution — everything
 *   CR 715.3b / CR 720.3b mean by *"while on the stack, the spell has only its alternative
 *   characteristics"*. It is a [SpellDefinition] because that is precisely what a castable set of
 *   characteristics is; the engine substitutes it for the card's own for the whole of a face cast.
 */
data class AlternativeFace(
    val kind: FaceKind,
    val definition: SpellDefinition,
) {
    init {
        val printed = definition.characteristics
        // CR 205.3k: Adventure and Omen are *spell types*, so the face is an instant or a sorcery and
        // carries the matching subtype. A face declared without it would be a spell of some other type
        // wearing the mechanic's resolution rule, which is exactly the plausible-looking wrong card
        // PLAN.md §7 refuses.
        require(CardType.INSTANT in printed.cardTypes || CardType.SORCERY in printed.cardTypes) {
            "CR 205.3k: the alternative characteristics of \"${printed.name}\" must be an instant or a " +
                "sorcery, but its card types are ${printed.cardTypes}"
        }
        require(printed.hasSubtype(kind.spellType)) {
            "CR 205.3k: a $kind face is subtyped ${kind.spellType.value}, but \"${printed.name}\" prints " +
                "${printed.subtypes}"
        }
        // CR 715.3a / CR 720.3a: "only the alternative characteristics are evaluated to see if it can be
        // cast", and a cast needs a cost to evaluate (CR 601.2f).
        require(printed.manaCost != null) {
            "CR 601.2f: the alternative characteristics of \"${printed.name}\" must print a mana cost"
        }
        // A face is one extra set of characteristics, not a chain of them (CR 715.2, CR 720.2).
        require(definition.alternativeFace == null) {
            "CR 715.2: \"${printed.name}\" is itself a face and cannot carry one of its own"
        }
    }
}

/**
 * Which two-faced-card rule an [AlternativeFace] follows. Additive, flagged core (`W10-B`).
 *
 * **They differ in exactly one clause and are nonetheless two rules**, which is why this is an enum
 * rather than a boolean named after one of them: CR 715 and CR 720 are separate sections with separate
 * frames, separate spell types and separate glossary entries, and the day a third inset-frame mechanic
 * arrives it is a member here rather than a second boolean.
 */
enum class FaceKind {
    /**
     * Adventure (CR 715). The face's spell is exiled as it resolves instead of going to its owner's
     * graveyard, and for as long as the card stays exiled that player may play it — its **normal**
     * half only, never the Adventure again (CR 715.3d).
     */
    ADVENTURE,

    /**
     * Omen (CR 720). The face's spell is **shuffled into its owner's library** as it resolves instead
     * of going to their graveyard (CR 720.3d). There is no later cast from exile at all: the card goes
     * back into the deck and may simply be drawn again.
     */
    OMEN,
    ;

    /** The spell type (CR 205.3k) the face's type line prints: `Sorcery — Adventure`, `Sorcery — Omen`. */
    val spellType: Subtype
        get() =
            when (this) {
                ADVENTURE -> ADVENTURE_TYPE
                OMEN -> OMEN_TYPE
            }

    private companion object {
        /** The Adventure spell type (CR 205.3k). */
        val ADVENTURE_TYPE: Subtype = Subtype("Adventure")

        /** The Omen spell type (CR 205.3k). */
        val OMEN_TYPE: Subtype = Subtype("Omen")
    }
}
