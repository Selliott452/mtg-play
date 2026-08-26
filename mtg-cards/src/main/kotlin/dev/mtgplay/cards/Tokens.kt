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
 * **Writhing Chrysalis stays out (`W9-F`), and needs two frameworks rather than one.** Its oracle text
 * is *"Devoid. / When you cast this spell, create two 0/1 colorless Eldrazi Spawn creature tokens with
 * 'Sacrifice this token: Add {C}.' / Reach / Whenever you sacrifice another Eldrazi, put a +1/+1 counter
 * on this creature."* Three of its four lines are expressible **today** and are not the blocker, which
 * is worth recording because the wave-8 triage filed the card under devoid:
 *
 * - **Devoid is not a blocker.** [dev.mtgplay.core.card.Keyword.DEVOID] exists and
 *   [dev.mtgplay.core.card.PrintedCharacteristics] reads it, which is more CR-correct than a layer-5
 *   effect would be: CR 702.114a makes devoid a characteristic-defining ability, so it functions in
 *   every zone rather than only on the battlefield.
 * - **Reach is not a blocker** ([dev.mtgplay.core.card.Keyword.REACH]).
 * - **The Eldrazi Spawn token is not a blocker.** [TokenDefinition] carries `manaAbilities`, so
 *   "Sacrifice this token: Add `{C}`" is `ManaAbility(options = [COLORLESS], cost = [SacrificeSelf])` —
 *   the same shape [tinderWall]'s ritual takes, verified rather than assumed.
 *
 * What is missing is two *trigger* conditions, and one of them arrived from a sibling packet while
 * `W9-F` was in flight:
 *
 * 1. **"When you cast this spell" is an ability of the spell *on the stack*** (CR 603.2, CR 113.6a).
 *    [dev.mtgplay.core.definition.TriggerCondition.SpellCast] is the wrong shape entirely: it is the
 *    *other-object* watcher a permanent on the battlefield has (Guttersnipe, God-Pharaoh's Faithful),
 *    detected at `completeCast` against permanents already in play. This one's source is the spell
 *    itself, on the stack and not yet resolved, so it needs a **Stack** zone scope.
 *
 *    When this packet branched, [dev.mtgplay.core.definition.TriggerZoneScope] had `Battlefield`,
 *    `Exile` and `Graveyard` and no more. **`TriggerZoneScope.Stack` has since landed on `main`** —
 *    `W9-C` added it for storm and `W9-G` for cascade — so what a later packet needs here is only a
 *    plain `CastSelf` condition on top of the scope those two already built, plus its detection inside
 *    the cast pipeline. Do not build a third zone scope.
 *
 *    **Encoding it as an enters-the-battlefield trigger is the trap**, and it is invisible on an
 *    uncontested cast: the same two tokens arrive, one priority round later. It gives the *wrong* board
 *    in exactly the case the card is played for — a countered Writhing Chrysalis still makes its Spawn,
 *    because the trigger is an independent object (CR 113.7a) that has already resolved. That is the
 *    whole reason the line is printed as a cast trigger, so approximating it deletes a real line of play
 *    (PLAN.md §7).
 * 2. **"Whenever you sacrifice another Eldrazi" has no watcher and no subtype axis**, and this is the
 *    one that is genuinely absent. No [dev.mtgplay.core.definition.TriggerCondition] member observes a
 *    CR 701.17 sacrifice at all — the nearest, `PutIntoGraveyardFromBattlefieldSelf`, is a *dies*
 *    trigger about the source itself, and sacrifice is a narrower event than dying (a sacrificed
 *    permanent is never destroyed, and a creature that dies to lethal damage was not sacrificed).
 *
 *    It is **smaller than a fan-out**, and saying so is the honest half of the diagnosis: every
 *    sacrifice in the engine — the CR 601.2h cast costs, the CR 602.1 ability costs, a mana ability's
 *    own cost, and the effect-side `sacrificePermanent` — funnels through a single
 *    `sacrificeOnePermanent`, exactly as every battlefield entry funnels through
 *    `announceBattlefieldEntry`. So this is one detection site, plus a condition member carrying a
 *    **creature-subtype** filter and an "another" exclusion of the source. A packet that owns the
 *    trigger vocabulary should take it; this one does not.
 *
 * Shipping the 2/3 reach body without either trigger would be a colourless bear wearing the card's
 * name: the Spawn are the card's mana and the counters are what the Spawn are spent on. Its absence is
 * pinned in `TokensSpec`, so a later packet that ships it deletes an assertion rather than quietly
 * adding a half-card.
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
