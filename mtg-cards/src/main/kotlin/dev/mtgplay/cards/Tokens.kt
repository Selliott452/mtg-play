package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.engine.matchingPermanents
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's token-making spell and the token it makes (`W8-E`).
 *
 * Rally at the Hornburg is the pool's first card to create **more than one** token from a single
 * resolution and the first to grant a keyword to a *set* of permanents rather than to one target, and
 * the two halves are ordered rather than independent: the tokens it creates are Humans, so they are in
 * the set the second sentence pumps. Both halves are published `mtg-rules` primitives ([createToken],
 * [applyUntilEndOfTurn], [matchingPermanents]) and this file is the fold over them — a mass grant is a
 * per-permanent effect applied to a membership, not a new kind of continuous effect, so it is
 * deliberately *not* a new primitive (ADR-003).
 *
 * ---
 *
 * **Writhing Chrysalis is no longer out** — `W10-D` shipped it, and this header's `W9-F` diagnosis was
 * right on every count, which is why it is worth recording that it was replaced rather than corrected.
 * Devoid, reach and the Eldrazi Spawn token were re-checked and were never blockers; the two missing
 * pieces were exactly the two trigger conditions it named. "When you cast this spell" became
 * [dev.mtgplay.core.definition.TriggerCondition.CastSelf] on the [TriggerZoneScope.Stack] that `W9-C`
 * and `W9-G` had already built — no third zone scope — and "whenever you sacrifice another Eldrazi"
 * became [dev.mtgplay.core.definition.TriggerCondition.YouSacrificedAnother], **one** detection site
 * rather than a fan-out, for the reason `W9-F` gave: every sacrifice in the engine funnels through one
 * private function. The card and both arguments live in `CastTriggers.kt`; `TokensSpec`'s absence pin
 * became a presence pin in the same commit.
 */

/** The Human creature type (CR 205.3m) Rally at the Hornburg prints on its tokens and pumps. */
private val HUMAN: Subtype = Subtype("Human")

/** The Soldier creature type (CR 205.3m) Rally at the Hornburg's tokens carry. */
private val SOLDIER: Subtype = Subtype("Soldier")

/** How many Human Soldier tokens Rally at the Hornburg creates (CR 111.4, CR 707.2). */
const val RALLY_AT_THE_HORNBURG_TOKENS: Int = 2

private val RALLY_AT_THE_HORNBURG: CardRef = CardRef("Rally at the Hornburg")

/** The **Humans its controller controls** that Rally at the Hornburg's second sentence pumps (CR 205.3). */
private val YOUR_HUMANS: PermanentFilter = PermanentFilter(subtype = HUMAN, controlledByYou = true)

/**
 * The 1/1 white Human Soldier creature token Rally at the Hornburg creates (CR 111.4).
 *
 * "White" is flavour the MVP models nowhere, exactly as [birdIllusionToken] records: colour is derived
 * from a mana cost and a token has none, and the CR 204 colour indicator stays unmodeled until a card
 * cares about a token's colour. Nothing in the gauntlet asks a token its colour, so the token is left
 * colourless-by-model without loss.
 *
 * The **creature types are not flavour**, and this is the token that shows why: Rally at the Hornburg's
 * own second sentence gives haste to "Humans you control", so a token printed without its Human type
 * would be created by the spell and then quietly skipped by the same spell's pump — the two Soldiers
 * would arrive summoning sick and the card would do half of what it prints.
 */
val humanSoldierToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Human Soldier",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(HUMAN, SOLDIER),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
            ),
    )

/**
 * Rally at the Hornburg — `{1}{R}` Sorcery. "Create two 1/1 white Human Soldier creature tokens.
 * Humans you control gain haste until end of turn."
 *
 * **The two sentences are ordered, and the order is the card** (CR 608.2: a resolving spell follows its
 * instructions in the order written). The tokens are created first, so they are already on the
 * battlefield when the second sentence takes its membership, and the haste it grants them is what makes
 * a two-mana sorcery two points of immediate damage instead of nothing this turn. Writing the pump
 * first would compile, pass a token-count test, and be a different card.
 *
 * **"Humans you control", not "creatures you control" and not "the tokens"** (CR 205.3, CR 109.5). The
 * membership is taken from the whole battlefield, so an already-summoning-sick Human cast earlier this
 * turn is pumped too — the reason the card is played in a Humans shell rather than as a bare
 * two-token sorcery. It is read through [matchingPermanents], the one changeling-aware seam
 * (CR 702.73a), so a Shapeshifter its controller controls is a Human here and gains haste.
 *
 * **The grant is a per-permanent until-end-of-turn effect, one per member** (CR 611.2, CR 514.2), not a
 * single effect over a set: [applyUntilEndOfTurn] fixes its affected object when the effect begins
 * (CR 611.2c), which is exactly the CR-correct behaviour for a "creatures you control gain X" that has
 * already resolved — a Human entering *after* this resolves gains nothing, and one that leaves takes
 * its own effect with it. The membership is therefore snapshotted here, in the resolution, for the same
 * reason Timberwatch Elf's count is (CR 608.2h, docs/gauntlet-card-triage.md T16).
 *
 * A Human that already has haste gets a redundant second instance (CR 702.10d), which is free and
 * correct: keywords are a set, and the grant cannot be the empty modification [ContinuousModification]
 * refuses.
 */
val rallyAtTheHornburg: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = RALLY_AT_THE_HORNBURG.name,
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val withTokens =
                    (1..RALLY_AT_THE_HORNBURG_TOKENS).fold(state) { current, _ ->
                        createToken(current, context.controller, humanSoldierToken)
                    }
                // CR 608.2: the second sentence, and its membership includes the tokens just created.
                matchingPermanents(withTokens, YOUR_HUMANS, context.controller)
                    .map { it.id }
                    .fold(withTokens) { current, human ->
                        applyUntilEndOfTurn(
                            state = current,
                            affected = human,
                            modification = ContinuousModification(grantedKeywords = persistentSetOf(Keyword.HASTE)),
                            sourceCard = RALLY_AT_THE_HORNBURG,
                        )
                    }
            }
    }
