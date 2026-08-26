package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.exileTopCardsPlayableUntilEndOfYourNextTurn
import dev.mtgplay.rules.effect.gainLife
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The `W8-E` creatures: bodies whose printed work happens as they arrive, plus the two whose printed
 * work happens somewhere the engine had never run an ability from.
 *
 * Five cards, and between them they open five seams:
 *
 * - [faerieMiscreant] — an **intervening-if** on an enters-the-battlefield trigger (CR 603.4), and the
 *   first whose two checks can genuinely disagree ([InterveningIf.YouControlAnotherCreatureNamed]).
 * - [godPharaohsFaithful] — a cast trigger filtered by the cast spell's **colour** (CR 105.2),
 *   [TriggerCondition.SpellCast.spellColors].
 * - [gatecreeperVine] — an **optional** library search (CR 601.3b) whose filter is a **disjunction**
 *   ("a basic land card or a Gate card"), the two axes [LibrarySearch] gained here.
 * - [brambleWurm] — an activated ability that functions from the **graveyard** (CR 113.6b) and pays for
 *   itself by leaving it ([AbilityZoneScope.Graveyard], [AbilityCost.ExileSelfFromGraveyard]).
 * - [trollOfKhazadDum] — a blocker-**count** restriction (CR 509.1b), the first evasion that is a
 *   property of the whole declaration rather than of a (blocker, attacker) pairing.
 *
 * Every mechanism composes a published `mtg-rules` primitive (ADR-003); nothing here reimplements a
 * rule, and the two new cost/zone members are declarations the engine interprets, not card-local code.
 */

/** The resolution of a permanent spell with no CR 608.2c instructions of its own (CR 608.3). */
private val entersTheBattlefield: ResolutionEffect = ResolutionEffect { state, _ -> state }

/** How many cards Faerie Miscreant's enters-the-battlefield trigger draws (CR 120.1). */
const val FAERIE_MISCREANT_DRAW: Int = 1

/** The life God-Pharaoh's Faithful gains per qualifying cast (CR 119.3). */
const val GOD_PHARAOHS_FAITHFUL_LIFEGAIN: Int = 1

/** The life Bramble Wurm gains, on entry and again from its graveyard (CR 119.3). */
const val BRAMBLE_WURM_LIFEGAIN: Int = 5

/** How many cards Clockwork Percussionist's dies trigger exiles for play (CR 701.3a, CR 118.5). */
const val CLOCKWORK_PERCUSSIONIST_EXILE: Int = 1

/** The Gate land type (CR 205.3b) Gatecreeper Vine's search names beside the basic lands. */
private val GATE: Subtype = Subtype("Gate")

/**
 * Faerie Miscreant — `{U}` Creature — Faerie Rogue, a 1/1. "Flying. When this creature enters, if you
 * control another creature named Faerie Miscreant, draw a card."
 *
 * **The card the intervening-if clause (CR 603.4) exists for in its interesting form.** Goblin
 * Bushwhacker's "if it was kicked" reads a fact frozen when the permanent entered, so its two checks
 * can never disagree and only the *firing* one is observable. This one reads the live battlefield in
 * both directions:
 *
 * - the first Miscreant of the game triggers **nothing** — no ability goes on the stack, no priority
 *   round opens for it, and the opponent is never invited to respond to a trigger that the rules say
 *   never happened;
 * - the second one fires, and if its partner is killed in response the ability is removed from the
 *   stack doing nothing (CR 603.4), so the draw is a real thing an opponent can answer.
 *
 * Writing the condition as an `if` inside the [ResolutionEffect] would get the second bullet right and
 * the first one wrong, which is invisible in the final board state and wrong in the enumerated action
 * space — the ADR-005 defect this engine cares most about.
 *
 * **"Another creature *named* Faerie Miscreant"** is a name comparison (CR 201.2), the pool's first,
 * and "another" is an object exclusion (CR 109.1) rather than a name one — so a second copy of the same
 * card satisfies it and the Miscreant never satisfies itself. It is deliberately *not* written as "two
 * or more Faerie Miscreants": once the source has left the battlefield there is nothing left to
 * exclude, and every Miscreant you control is "another" one.
 */
val faerieMiscreant: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Faerie Miscreant",
                manaCost = ManaCost.parse("{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Faerie"), Subtype("Rogue")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.FLYING),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    interveningIf = InterveningIf.YouControlAnotherCreatureNamed("Faerie Miscreant"),
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, FAERIE_MISCREANT_DRAW)
                        },
                ),
            )
    }

/**
 * God-Pharaoh's Faithful — `{W}` Creature — Human Wizard, a 0/4. "Whenever you cast a blue, black, or
 * red spell, you gain 1 life."
 *
 * The cast-trigger seam filtered by **colour** (CR 105.2) rather than by card type, which is a genuinely
 * different question and so a fourth filter on [TriggerCondition.SpellCast] rather than a reading of the
 * existing ones. A spell is every colour in its mana cost (CR 202.2), so a hybrid or gold spell fires
 * this as soon as one of its colours is listed.
 *
 * **A colourless spell fires nothing**, and that half is load-bearing rather than pedantic: this is a
 * white one-drop that sits in artifact-heavy shells, and reading the line as "any spell you cast" would
 * hand the seat a life per Myr Enforcer it never had. It is also why the colours are the *cast card's*
 * own and not the colours of the mana that paid for it — a red Fireblast cast for its alternative cost,
 * paying no mana at all, is still a red spell and still fires this.
 *
 * A 0/4 body with no evasion and no combat text: the trigger is the whole card, and the toughness is
 * what keeps it alive to accumulate it.
 */
val godPharaohsFaithful: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "God-Pharaoh's Faithful",
                manaCost = ManaCost.parse("{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Human"), Subtype("Wizard")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = 4),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition =
                        TriggerCondition.SpellCast(
                            spellColors = persistentSetOf(Color.BLUE, Color.BLACK, Color.RED),
                            controlledByYou = true,
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, GOD_PHARAOHS_FAITHFUL_LIFEGAIN)
                        },
                ),
            )
    }

/**
 * Gatecreeper Vine — `{1}{G}` Creature — Plant, a 0/2. "Defender. When this creature enters, you may
 * search your library for a basic land card or a Gate card, reveal it, put it into your hand, then
 * shuffle."
 *
 * Two printed details that no filter in the pool could express before, and both are real lines of play:
 *
 * - **"a basic land card **or** a Gate card"** is a *disjunction*, not a narrowing. Every search filter
 *   before this one ANDed its axes, and no `(basic, landTypes)` pair selects the union of "every basic"
 *   and "every Gate" — a basic Forest is not a Gate and a Gate is not basic. Encoding it as either
 *   half alone would delete the other fetch entirely, which for a Gates deck is the difference between
 *   fixing colours and finding the land its payoffs count.
 * - **"you may search"** (CR 601.3b) is not CR 701.18b's always-available "fail to find". Failing to
 *   find still shuffles — "then shuffle" is a separate instruction that happens regardless — where
 *   declining never starts the search and shuffles nothing. Both are enumerated as their own indices
 *   (ADR-005), because a seat that has just arranged its top cards wants exactly one of them and a seat
 *   that dislikes them wants exactly the other.
 *
 * The search is a [LibrarySearch] clause on the **trigger**, which `FW-CLAUSEHOOK` made possible: it is
 * the same CR 701.18 orchestration Ash Barrens' landcycling uses, hanging off CR 603 instead of CR 602.
 *
 * Defender (CR 702.3b) is printed and honoured: the Vine is never offered as an attacker, and blocks
 * normally behind its 2 toughness.
 */
val gatecreeperVine: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gatecreeper Vine",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Plant")),
                powerToughness = PrintedPowerToughness(power = 0, toughness = 2),
                keywords = persistentSetOf(Keyword.DEFENDER),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    // Everything the trigger does is its clause, which the engine runs after this no-op.
                    effect = entersTheBattlefield,
                    librarySearch =
                        LibrarySearch(
                            find = LibrarySearchFilter.basicOrOneOf(setOf(GATE)),
                            optional = true,
                        ),
                ),
            )
    }

/**
 * Bramble Wurm — `{6}{G}` Creature — Wurm, a 7/6. "Reach, trample. When this creature enters, you gain
 * 5 life. `{2}{G}`, Exile this card from your graveyard: You gain 5 life."
 *
 * **The pool's first ability that functions from a graveyard** (CR 113.6b), and the reason
 * [AbilityZoneScope.Graveyard] and [AbilityCost.ExileSelfFromGraveyard] exist. An ability of a card in a
 * graveyard normally does nothing there; only one that says it functions from a graveyard does, and this
 * one says so by naming the zone in its own cost.
 *
 * The card is therefore two lifegains that never both happen from the same copy: the entry trigger
 * (CR 603.6a) when the seven-drop actually lands, and the graveyard ability when it does not — which is
 * the point of a `{6}{G}` common in a ramp deck. **Paying the cost exiles the card**, so the graveyard
 * half is once and only once, with no [ActivatedAbility.oncePerTurn] restriction needed to say so; and
 * because it is a *cost*, the exile happens on activation and is not undone if the ability is countered.
 *
 * Reach and trample are both printed and both live: reach lets the 7/6 wall off a flyer, and trample
 * (CR 702.19) surfaces the excess-assignment decision when it is chump-blocked. The body has no evasion,
 * so it is blocked by any one creature — which is exactly the contrast [trollOfKhazadDum] draws.
 */
val brambleWurm: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Bramble Wurm",
                manaCost = ManaCost.parse("{6}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Wurm")),
                powerToughness = PrintedPowerToughness(power = 7, toughness = 6),
                keywords = persistentSetOf(Keyword.REACH, Keyword.TRAMPLE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, BRAMBLE_WURM_LIFEGAIN)
                        },
                ),
            )
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.Mana(ManaCost.parse("{2}{G}")),
                            AbilityCost.ExileSelfFromGraveyard,
                        ),
                    zoneScope = AbilityZoneScope.Graveyard,
                    effect =
                        ResolutionEffect { state, context ->
                            gainLife(state, context.controller, BRAMBLE_WURM_LIFEGAIN)
                        },
                ),
            )
    }

/**
 * Troll of Khazad-dûm — `{5}{B}` Creature — Troll, a 6/5. "This creature can't be blocked except by
 * three or more creatures. Swampcycling `{1}` (`{1}`, Discard this card: Search your library for a Swamp
 * card, reveal it, put it into your hand, then shuffle.)"
 *
 * **Two halves that are almost never used in the same game**, which is the whole design of the card: it
 * is a Swamp on turn two and a six-power unanswerable attacker on turn six, and the deck plays it as a
 * land that occasionally wins.
 *
 * The swampcycling half is [AbilityZoneScope.Hand] plus [AbilityCost.DiscardSelf] and a
 * [LibrarySearch] clause — the exact shape Ash Barrens' basic landcycling already had, narrowed to
 * [LibrarySearchFilter.SWAMP_CARD]. Typecycling names a land *subtype* and never the basic land
 * (CR 702.28b), so a nonbasic Swamp is an equally legal find; that is the filter's business and not
 * this card's.
 *
 * The evasion half is [Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE], and it is the first block restriction
 * in the engine that is **not** a property of a (blocker, attacker) pairing (CR 509.1b). Every evasion
 * before it was answered by looking at one blocker; this one is answered only by looking at the whole
 * declaration, so `mtg-rules` publishes it as a per-attacker minimum on the declare-blockers request and
 * enforces it across the chosen set. Three consequences the printed line does *not* say and which the
 * engine gets right by construction:
 *
 * - **blocking with none is always legal** — the restriction says how it may be blocked, never that it
 *   must be, so a defender with three untapped creatures is never forced to throw them under it;
 * - **killing two of the three afterwards does not unblock it** (CR 506.4, CR 509.1h) — the check
 *   happens as blockers are declared and never again, which is why the Troll is beaten by having three
 *   bodies rather than by having three survivors;
 * - **the three blockers are ordered for damage** (CR 509.2) exactly as any triple block is, so the
 *   6/5 kills what it can through the ordering it chose.
 */
val trollOfKhazadDum: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Troll of Khazad-dûm",
                manaCost = ManaCost.parse("{5}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Troll")),
                powerToughness = PrintedPowerToughness(power = 6, toughness = 5),
                evasions = persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_THREE_OR_MORE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    // CR 702.28a: swampcycling {1} is "{1}, Discard this card: Search your library …".
                    cost = persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.DiscardSelf),
                    zoneScope = AbilityZoneScope.Hand,
                    effect = entersTheBattlefield,
                    librarySearch = LibrarySearch(find = LibrarySearchFilter.SWAMP_CARD),
                ),
            )
    }

/**
 * Clockwork Percussionist — `{R}` Artifact Creature — Monkey Toy, a 1/1. "Haste. When this creature
 * dies, exile the top card of your library. You may play it until the end of your next turn."
 *
 * **A one-mana hasty body that replaces itself when it trades**, which is why the red shells run it: it
 * attacks the turn it lands, and the card it gives back arrives *after* the trade rather than before,
 * so it is card advantage the opponent cannot deny by killing it.
 *
 * **The card `W8-E` dropped, and it is now pure composition.** That packet ceded it rather than race
 * `W8-D` for the play-from-exile permission, and `W8-D` built exactly the primitive it needed:
 * [exileTopCardsPlayableUntilEndOfYourNextTurn], which records the grant on the exiled object as
 * [dev.mtgplay.core.state.GameObject.playGrantedTurn] and leaves `mtg-rules` to enumerate both halves
 * of "play" from exile. There is no new engine mechanism here at all.
 *
 * **Its entry point into that primitive is not Reckless Impulse's, and the primitive already covers
 * both.** Reckless Impulse grants from a resolving *sorcery*; this grants from a resolving *triggered
 * ability* whose source is already in a graveyard. The primitive is a plain function of state and
 * player, so neither the kind of resolving object nor the source's zone reaches it, and the "you" it
 * exiles from is [dev.mtgplay.core.definition.ResolutionContext.controller] — for a
 * leaves-the-battlefield trigger, the last-known controller captured as the permanent left (CR 603.10),
 * which is the right player even though the Percussionist itself no longer exists.
 *
 * **"Dies" is CR 700.4** — put into a graveyard *from the battlefield* — so this is
 * [TriggerCondition.PutIntoGraveyardFromBattlefieldSelf] and deliberately **not**
 * [TriggerCondition.LeftBattlefieldSelf]: a Percussionist exiled or bounced gives nothing back, and
 * encoding the general departure would hand its controller a free card off every answer that is not a
 * kill.
 *
 * **"Until the end of your next turn" is a real deadline, not a formality.** Traded on the opponent's
 * turn the exiled card is playable through the whole of the following turn; traded on its controller's
 * own turn it is playable for the rest of that turn and all of the next. The engine decides that
 * without predicting the turn order by recording when the grant *began* rather than when it ends (see
 * [dev.mtgplay.core.state.GameObject.playGrantedTurn]).
 */
val clockworkPercussionist: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Clockwork Percussionist",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.ARTIFACT, CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Monkey"), Subtype("Toy")),
                powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                keywords = persistentSetOf(Keyword.HASTE),
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = entersTheBattlefield
        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    // CR 700.4: "dies" is exactly "put into a graveyard from the battlefield".
                    condition = TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            exileTopCardsPlayableUntilEndOfYourNextTurn(
                                state,
                                context.controller,
                                CLOCKWORK_PERCUSSIONIST_EXILE,
                            )
                        },
                ),
            )
    }
