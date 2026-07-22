package dev.mtgplay.acceptance

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.identity.CardRef
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Synthetic Aura shapes two of the layer PROPERTIES need that the real seven Bogles Auras do not
 * isolate on their own (docs/design/layer-system.md §8, "plus whatever synthetic effect shapes the
 * properties need"):
 *
 *  - a keyword-grant-ONLY Aura (layer 6, no layer-7c modifier) to prove a grant never moves P/T —
 *    every real keyword Aura also carries a +X/+Y, so none isolates the grant;
 *  - an EMPTY-effect Aura (no grant, no modifier) to reach the loud gate — an effect that classifies
 *    into no populated layer must throw through the public accessor, never silently drop (§1).
 *
 * These are card-definition *data* only; they are never cast, just placed on a handcrafted battlefield,
 * so a plain [CardDefinition] (not a castable [dev.mtgplay.core.definition.SpellDefinition]) suffices.
 * The engine reads `attachedTo` and `staticContinuousEffects`, not the source's type, so the classifier
 * treats them exactly as it treats the real Auras.
 */

/** A keyword-grant-only Aura granting trample (CR 702.19) with no P/T modifier — the layer-isolation probe. */
internal const val TRAMPLE_SIGIL = "Synthetic Trample Sigil"

/** A keyword-grant-only Aura granting vigilance (CR 702.21) with no P/T modifier — a second isolated grant. */
internal const val VIGILANCE_SIGIL = "Synthetic Vigilance Sigil"

/** An Aura whose static continuous effect is empty — classifies into no populated layer (the loud gate, §1). */
internal const val HOLLOW_AURA = "Synthetic Hollow Aura"

private fun syntheticAura(
    name: String,
    effect: StaticContinuousEffect,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ENCHANTMENT),
                subtypes = persistentSetOf(Subtype("Aura")),
                powerToughness = null,
            )
        override val staticContinuousEffects: PersistentList<StaticContinuousEffect> = persistentListOf(effect)
    }

/** A keyword-grant-only Aura [name] granting [keyword] with no layer-7c modifier (CR 613, layer 6). */
private fun keywordGrantAura(
    name: String,
    keyword: Keyword,
): CardDefinition = syntheticAura(name, StaticContinuousEffect(grantedKeywords = persistentSetOf(keyword)))

/**
 * The [MvpCards] registry extended with the synthetic Auras above — the definition map the isolation and
 * loud-gate properties build handcrafted boards over. The real cards are unchanged; the synthetics only
 * add effect shapes the pinned pool omits.
 */
internal val syntheticLayerDefinitions: Map<CardRef, CardDefinition> =
    MvpCards.definitions +
        listOf(
            keywordGrantAura(TRAMPLE_SIGIL, Keyword.TRAMPLE),
            keywordGrantAura(VIGILANCE_SIGIL, Keyword.VIGILANCE),
            syntheticAura(HOLLOW_AURA, StaticContinuousEffect()),
        ).associateBy { CardRef(it.characteristics.name) }
