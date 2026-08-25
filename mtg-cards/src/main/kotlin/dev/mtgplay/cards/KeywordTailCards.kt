package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.createToken
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.exileCardFromGraveyard
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's remaining keyword tail — deathtouch (CR 702.2) and changeling (CR 702.73) — plus the
 * two framework halves the same cards needed: a **conditional** static ability (`FW-CONDSTATIC`) and a
 * **granted** evasion.
 *
 * Every card here was re-fetched from `POST https://api.scryfall.com/cards/collection` (descriptive
 * `User-Agent`, 5/5 found, `not_found` empty) before any code was written; oracle text beats both the
 * triage and the packet brief. Two disagreements are recorded on the cards they affect: Toxin Analysis
 * also prints **Investigate**, which the brief omits (and which turned out to be free — see
 * [clueToken]), and Gingerbrute's second ability is a **third** printed line the brief does not
 * mention. Clockwork Percussionist was dropped; see the packet report and the note on [gingerbrute].
 */

/** The life Rooftop Percher's enters-the-battlefield trigger gains (CR 119.3). */
const val ROOFTOP_PERCHER_LIFEGAIN: Int = 3

/** The graveyard cards Rooftop Percher's enters-the-battlefield trigger may exile ("up to two"). */
const val ROOFTOP_PERCHER_TARGETS: Int = 2

/** The life Gingerbrute's sacrifice ability gains (CR 119.3). */
const val GINGERBRUTE_LIFEGAIN: Int = 3

/** The cards a Clue token's sacrifice ability draws (CR 121.1). */
const val CLUE_TOKEN_DRAW: Int = 1

/** The power Goblin Tomb Raider's conditional static ability adds while you control an artifact. */
const val GOBLIN_TOMB_RAIDER_POWER_BONUS: Int = 1

private val TOXIN_ANALYSIS: CardRef = CardRef("Toxin Analysis")

/**
 * The Clue token (CR 111.4) **Investigate** creates (CR 701.50a): a colorless artifact token with
 * subtype Clue and "{2}, Sacrifice this token: Draw a card."
 *
 * Investigate is a keyword *action*, not a keyword ability, and it turned out to cost this packet
 * nothing: the token is [foodToken] and [bloodToken]'s shape with a different cost, composing
 * [createToken], [AbilityCost.Mana], [AbilityCost.SacrificeSelf] and [drawCards], all published. The
 * only detail worth stating is what the cost does **not** contain — there is no `{T}` — so a Clue may
 * be cracked the turn it is created and CR 302.6 never enters into it (it is not a creature anyway).
 */
val clueToken: TokenDefinition =
    TokenDefinition(
        characteristics =
            PrintedCharacteristics(
                name = "Clue",
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Clue")),
                powerToughness = null,
            ),
        activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, CLUE_TOKEN_DRAW)
                        },
                ),
            ),
    )

/**
 * Toxin Analysis — `{B}` Instant. "Target creature gains deathtouch and lifelink until end of turn.
 * Investigate."
 *
 * The demonstration card for [Keyword.DEATHTOUCH], and it is a *grant* rather than a printed keyword —
 * which is the shape that forced the keyword to be honoured through the layer system from its first
 * day rather than read off a card. Nothing in the gauntlet prints deathtouch at all, so had the
 * lethality sites read printed characteristics the keyword would have been untestable and, worse,
 * would have looked implemented.
 *
 * **Oracle disagreement with the packet brief, flagged.** The brief describes this card as blocked on
 * deathtouch alone; the printed text also says **Investigate**, which the brief omits entirely. It
 * turned out to be free — a Clue token is [clueToken], built from published primitives — so the card
 * is carried whole rather than as a two-thirds encoding. Had Investigate needed anything new it would
 * have been the card's blocker, not deathtouch.
 *
 * Three details are load-bearing:
 * - **Both grants are one effect.** A single [ContinuousModification] carrying both keywords, not two
 *   stored effects: they share a duration, a source and a timestamp, and splitting them would put two
 *   entries in the store where the card creates one.
 * - **"Target creature"** is [PermanentRestriction.CREATURE] with no control clause, so the deathtouch
 *   may be handed to an *opponent's* creature — a real line of play (it makes their blocker trade with
 *   anything, and their attacker kill your blocker), and one the engine must offer under ADR-005.
 * - **The Clue is created even though the spell also fizzles as a whole if the target is gone**
 *   (CR 608.2b): with the creature illegal the spell does not resolve at all and no Clue appears. That
 *   is the CR answer and needs no card-side test, exactly as it does for Pulse of Murasa's lifegain.
 */
val toxinAnalysis: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = TOXIN_ANALYSIS.name,
                manaCost = ManaCost.parse("{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        override val resolution =
            ResolutionEffect { state, context ->
                val granted =
                    applyUntilEndOfTurn(
                        state = state,
                        affected = singleTargetedPermanent(context.targets, TOXIN_ANALYSIS.name),
                        modification =
                            ContinuousModification(
                                grantedKeywords = persistentSetOf(Keyword.DEATHTOUCH, Keyword.LIFELINK),
                            ),
                        sourceCard = TOXIN_ANALYSIS,
                        source = context.source,
                    )
                // CR 701.50a: to investigate is to create a Clue token.
                createToken(granted, context.controller, clueToken)
            }
    }

/**
 * Rooftop Percher — `{5}` Creature — Shapeshifter, a 3/3 with changeling and flying. "When this
 * creature enters, exile up to two target cards from graveyards. You gain 3 life."
 *
 * The pool's only [Keyword.CHANGELING], and the card that makes the keyword's cost visible: "this card
 * is every creature type" (CR 702.73a) is one line of reminder text and a rule that every subtype
 * predicate in the engine has to consult. Encoding it as a bare enum member would have left a
 * Shapeshifter that Wellwisher does not count as an Elf and Breath Weapon happily sweeps as a
 * non-Dragon — both silently wrong, both plausible in a log.
 *
 * **It is a Shapeshifter and it is not a Forest.** CR 702.73a grants creature types only, so the
 * changeling answer is gated on [dev.mtgplay.core.card.Subtype.isCreatureType]: this creature counts
 * for Priest of Titania and Wellwisher, and does **not** count for Gingerbread Cabin's Forests or
 * Fireblast's Mountains. That gate is the difference between the keyword being implemented and being a
 * board-wide bug, and it is asserted directly in this packet's tests.
 *
 * Everything past the changeling already existed. The enters-the-battlefield trigger is Archaeomancer's
 * shape (CR 603.6a, targets chosen as the trigger goes on the stack per CR 603.3d) carrying Faerie
 * Macabre's [TargetSpec.CardInGraveyard] with [TargetCount.UpTo] — the "up to two target cards from
 * graveyards" that `FW-ZONETGT` recorded this exact card as blocked on, and that `FW-MULTITGT` then
 * unblocked.
 *
 * **"Up to two" and "you gain 3 life" are independent.** The lifegain is not conditional on exiling
 * anything: with both graveyards empty the trigger still resolves, exiles nothing, and gains 3. That
 * follows from [TargetCount.UpTo] admitting zero targets, so the trigger never fizzles for want of one
 * (CR 608.2b needs *all* targets illegal, and it has none).
 */
val rooftopPercher: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Rooftop Percher",
                manaCost = ManaCost.parse("{5}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Shapeshifter")),
                powerToughness = PrintedPowerToughness(power = 3, toughness = 3),
                keywords = persistentSetOf(Keyword.CHANGELING, Keyword.FLYING),
            )

        // CR 302.1: a creature spell is cast at sorcery speed and targets nothing — the ability targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec =
                        TargetSpec.CardInGraveyard(
                            restriction = GraveyardCardRestriction.ANY_CARD,
                            scope = GraveyardScope.ANY,
                            count = TargetCount.UpTo(ROOFTOP_PERCHER_TARGETS),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            val exiled =
                                targetedGraveyardCards(context.targets, "Rooftop Percher")
                                    .fold(state, ::exileCardFromGraveyard)
                            gainLife(exiled, context.controller, ROOFTOP_PERCHER_LIFEGAIN)
                        },
                ),
            )
    }

/**
 * Goblin Tomb Raider — `{R}` Creature — Goblin Pirate, a 1/2. "As long as you control an artifact,
 * this creature gets +1/+0 and has haste."
 *
 * The demonstration card for `FW-CONDSTATIC`, and it needs all three of the framework's parts at once:
 * [AffectedSet.Self] (the effect modifies its own source, not an enchanted object),
 * [StaticCondition.YouControl] (the "as long as …" clause), and the artifact count behind it.
 *
 * **The condition is continuous, and that is the whole rule** (CR 604.3). There is no trigger and
 * nothing uses the stack: the haste and the +1/+0 exist exactly while an artifact is on the
 * battlefield under the same controller, appearing the instant one enters and vanishing the instant
 * the last one leaves — mid-turn, mid-combat, with no player receiving priority in between. Encoding
 * it as an enters-the-battlefield trigger granting an until-end-of-turn effect would look identical in
 * the common case and be wrong the moment the artifact is removed in response, which is precisely the
 * wrong-result-that-looks-right this engine refuses (PLAN.md §7).
 *
 * **The haste half is what makes the condition observable in the action space** (ADR-005). Attacker
 * eligibility reads haste through the effective-keyword seam, so the artifact's presence is the
 * difference between this creature being *offered* as an attacker on the turn it arrives and not
 * being offered at all — an enumerated option that exists or does not, never a rule applied afterwards.
 *
 * "An artifact" is any artifact you control, artifact *creatures* and artifact *lands* included — the
 * filter constrains [CardType.ARTIFACT] and nothing else. [gingerbrute] and the Mirrodin artifact lands
 * all turn it on.
 */
val goblinTombRaider: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Goblin Tomb Raider",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Goblin"), Subtype("Pirate")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 2),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val staticContinuousEffects =
            persistentListOf(
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition =
                        StaticCondition.YouControl(
                            filter = PermanentFilter(cardType = CardType.ARTIFACT, controlledByYou = true),
                        ),
                    grantedKeywords = persistentSetOf(Keyword.HASTE),
                    powerMod = Magnitude.Fixed(GOBLIN_TOMB_RAIDER_POWER_BONUS),
                ),
            )
    }

/**
 * Gingerbrute — `{1}` Artifact Creature — Food Golem, a 1/1 with haste. "`{1}`: This creature can't be
 * blocked this turn except by creatures with haste." / "`{2}`, `{T}`, Sacrifice this creature: You gain
 * 3 life."
 *
 * The demonstration card for the **granted** evasion, and the reason evasions needed a CR 613 layer-6
 * seam at all. Gingerbrute prints no evasion: its first ability *creates* one, so there is no printed
 * value for the block-legality check to read and the read had to move off the definition registry and
 * onto [dev.mtgplay.rules.engine.effectiveEvasions] before this card could exist.
 *
 * **"This turn" is CR 514.2's until-end-of-turn** — the same duration, differently worded — so the
 * ability composes [applyUntilEndOfTurn] unchanged. The oracle wording is worth recording because the
 * packet brief says "until end of turn"; the two are the same duration and the difference is
 * typographic, not semantic.
 *
 * **Three printed lines, all encoded, and the brief mentions one.** The second ability is [foodToken]'s
 * exact cost shape — `{2}` + `{T}` + sacrifice-self — which is not a coincidence: Gingerbrute *is* a
 * Food, and the printed ability is the standard Food ability on a creature body. Being a creature, its
 * `{T}` component is gated by CR 302.6 — except that Gingerbrute prints haste, which lifts the gate,
 * so it may sacrifice itself for 3 life the turn it arrives. That interaction is real and is asserted.
 *
 * **The evasion is granted to itself**, so the affected object is `context.source` rather than a
 * target: the ability targets nothing at all, and the CR 608.2b re-check has no work to do. A source
 * that has left the battlefield before the ability resolves is the one case that cannot arrive here,
 * because sacrificing it is not part of *this* ability's cost.
 *
 * Its type line carries **both** subtypes, and they belong to different card types: `Food` is an
 * artifact type (CR 205.3g) and `Golem` a creature type (CR 205.3m). That split is exactly what
 * [dev.mtgplay.core.card.CreatureType] classifies, and this card is the pool's clearest example of why
 * a changeling must be a Golem and must not be a Food.
 */
val gingerbrute: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gingerbrute",
                manaCost = ManaCost.parse("{1}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Food"), Subtype("Golem")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.HASTE),
            )

        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}"))),
                    effect =
                        ResolutionEffect { state, context ->
                            applyUntilEndOfTurn(
                                state = state,
                                affected = resolvingSource(context.source, "Gingerbrute"),
                                modification =
                                    ContinuousModification(
                                        grantedEvasions = persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_HASTE),
                                    ),
                                sourceCard = CardRef("Gingerbrute"),
                                source = context.source,
                            )
                        },
                ),
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}")),
                            AbilityCost.TapSelf,
                            AbilityCost.SacrificeSelf,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, GINGERBRUTE_LIFEGAIN)
                        },
                ),
            )
    }

/**
 * The single permanent [targets] names (CR 115.1b), for a spell whose spec is a
 * [TargetSpec.TargetPermanent]. Fails loudly on any other shape: the CR 608.2b re-check has already
 * run, so a resolving spell always holds exactly one legal permanent target (ADR-005).
 */
private fun singleTargetedPermanent(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $cardName targets exactly one permanent, got $targets")

/** The graveyard cards among [targets] (CR 115.1, CR 404), failing loudly on any other target kind. */
private fun targetedGraveyardCards(
    targets: List<Target>,
    cardName: String,
): List<ObjectId> =
    targets.map { target ->
        (target as? Target.CardInGraveyard)?.id
            ?: error("CR 115.1: $cardName targets only cards in graveyards, got $target")
    }

/**
 * The resolving ability's own source object (CR 113.7c), for an ability that modifies itself. Fails
 * loudly when the context carries none: an ability whose effect names "this creature" cannot proceed
 * without knowing which object that is, and guessing would apply the effect to the wrong permanent.
 */
private fun resolvingSource(
    source: ObjectId?,
    cardName: String,
): ObjectId = source ?: error("CR 113.7c: $cardName's ability modifies its own source, but the context records none")
