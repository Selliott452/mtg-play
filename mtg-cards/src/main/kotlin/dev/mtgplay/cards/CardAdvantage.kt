package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ChosenTypeReveal
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.drawCards
import dev.mtgplay.rules.effect.exileTopCardsPlayableUntilEndOfYourNextTurn
import dev.mtgplay.rules.effect.sacrificePermanent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's card-advantage engines — the creatures and spells whose whole job is to trade a card
 * for more than one.
 *
 * `W8-D` opens the file with **Mulldrifter**, which needed evoke (CR 702.74) and nothing else: the
 * draw-two half is [drawCards] on an ordinary enters-the-battlefield trigger, and every other card
 * advantage engine in the packet's scope turned out to be blocked on a framework the packet does not own
 * (see the drops recorded in the packet report and in GraveyardArtifacts.kt).
 *
 * Evoke is three small additions rather than one: a [CastingPermission.Evoke] member, the
 * [dev.mtgplay.core.state.GameObject.evokedWhenCast] flag that carries "this was evoked" across the
 * CR 400.7 spell→permanent boundary, and the [InterveningIf.SourceWasEvoked] clause that reads it.
 * Every piece has an exact precedent in kicker's, which is the point: the two keywords are the same
 * shape, and the second instance is what turns a special case into a mechanism.
 *
 * **Winding Way** joins them on [dev.mtgplay.core.definition.ChosenTypeReveal], the resolution-time type
 * choice CardSelection.kt named as its one remaining blocker, and **Reckless Impulse** on the
 * play-from-exile grant ([dev.mtgplay.rules.effect.exileTopCardsPlayableUntilEndOfYourNextTurn]) — the
 * first permission in the engine that is *granted by an effect to another object* rather than declared by
 * the card being played, and therefore the first that could not be a [CastingPermission].
 *
 * **Two of the packet's card-advantage cards are deliberately absent**, each blocked on a framework this
 * packet does not own; an approximation of either would be a plausible-looking wrong card (PLAN.md §7).
 *
 * - **Fanatical Offering** — `{1}{B}` Instant, "As an additional cost to cast this spell, sacrifice an
 *   artifact or creature. Draw two cards and create a Map token." Every part but the token is already
 *   expressible: [dev.mtgplay.core.definition.AdditionalCost.Sacrifice] carries "an artifact or creature"
 *   (Eviscerator's Insight prints the same line) and the draw is [drawCards]. The blocker is the **Map
 *   token**, whose ability is "{1}, {T}, Sacrifice this token: Target creature you control **explores**.
 *   Activate only as a sorcery." [dev.mtgplay.core.definition.TokenDefinition] can carry a targeted,
 *   sorcery-speed activated ability, so the token itself is not the gap — **explore** (CR 701.40) is: it
 *   reveals the top card, puts it into the hand if it is a land, and otherwise puts a `+1/+1` counter on
 *   the creature and then asks its controller to leave the card on top or bin it. That is a conditional
 *   mid-resolution decision, i.e. a new [dev.mtgplay.core.definition.ResolutionClauses] member with a
 *   branch no existing clause has. Encoding the card without the token would delete half of it — the
 *   token is why the card is played over a plain draw-two — so it waits.
 * - **Monstrous Emergence** — `{1}{G}` Sorcery, "As an additional cost to cast this spell, choose a
 *   creature you control **or** reveal a creature card from your hand. Monstrous Emergence deals damage
 *   equal to the power of the creature you chose or the card you revealed to target creature." The
 *   additional cost is a **choice between two differently-shaped costs**, and neither shape exists:
 *   [dev.mtgplay.core.definition.AdditionalCost] has [dev.mtgplay.core.definition.AdditionalCost.DiscardCards]
 *   and [dev.mtgplay.core.definition.AdditionalCost.Sacrifice], both of which *consume* the object they
 *   name, while this cost consumes nothing — it chooses or reveals, and the object stays where it is.
 *   Worse for an approximation, the two branches read power from different places: a chosen creature's is
 *   its CR 613 layered power on the battlefield, and a revealed card's is its **printed** power, because
 *   the layer system does not reach a hand (CR 109.3). A single "choose a creature" encoding would be a
 *   card that cannot be cast off an empty board, and a single "reveal from hand" one would silently
 *   ignore every pump effect. Both are the failure PLAN.md §7 names.
 */

/** The cards Mulldrifter's enters-the-battlefield trigger draws (CR 120.1). */
const val MULLDRIFTER_DRAW: Int = 2

/** How many cards Winding Way reveals from the top of its controller's library (CR 701.16a). */
const val WINDING_WAY_REVEAL: Int = 4

/** How many cards Reckless Impulse exiles from the top of its controller's library (CR 701.3a). */
const val RECKLESS_IMPULSE_EXILE: Int = 2

/**
 * Mulldrifter — `{4}{U}` Creature — Elemental 2/2. "Flying. When this creature enters, draw two cards.
 * Evoke {2}{U}."
 *
 * **Two cards for one, at a price chosen when it is cast.** Hard-cast it for `{4}{U}` and you get a 2/2
 * flier that drew two; evoke it for `{2}{U}` and you get the two cards and a corpse. Both lines are
 * enumerated at every priority window the card is castable in (ADR-005), because CR 702.74a's permission
 * is an *alternative* cost (CR 118.9) and a card with an alternative cost is still castable the normal
 * way.
 *
 * **Evoke is one keyword and two abilities** (CR 702.74a), and encoding only the first half is the trap
 * the [CastingPermission.Evoke] KDoc records: a permission alone gives a Mulldrifter that draws two for
 * `{2}{U}` and then *stays on the battlefield*. The second half is the [TriggeredAbility] below —
 * "When this permanent enters, **if its evoke cost was paid**, sacrifice it" — and CR 603.4's
 * intervening-if is what makes it silent on a hard cast: the ability does not merely fizzle, it never
 * goes on the stack at all, so a hard-cast Mulldrifter's controller is not asked to order two triggers
 * and no priority window opens for one.
 *
 * **Both triggers fire from the same event and the controller orders them** (CR 603.3b) — an enumerated
 * decision, and a real one even though the draw happens either way. Ordering the draw to resolve *first*
 * leaves the 2/2 on the battlefield through one more priority round, where it can be targeted, block, or
 * be sacrificed to something else; ordering the sacrifice first means the draw resolves with the
 * Mulldrifter already dead. The engine's existing APNAP trigger ordering supplies this; the card
 * declares two triggers and nothing about their sequence.
 *
 * The sacrifice trigger reads its own source off [dev.mtgplay.core.definition.ResolutionContext.source]
 * — an ability's source as of firing (CR 113.7c) — and [sacrificePermanent] is a no-op if that permanent
 * has already left, which is the ordinary outcome when the opponent kills it in response.
 *
 * **An evoked Mulldrifter is not "cast for free".** Its evoke cost is `{2}{U}`, a real payment, so the
 * cast is an ordinary CR 601 cast with an alternative cost — it can be countered, it triggers
 * spell-cast abilities, and its controller may respond to their own trigger before it resolves.
 */
val mulldrifter: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Mulldrifter",
                manaCost = ManaCost.parse("{4}{U}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Elemental")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
                keywords = persistentSetOf(Keyword.FLYING),
            )

        // CR 302.1: a creature spell is cast at sorcery speed and targets nothing.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val castingPermissions =
            listOf(CastingPermission.Evoke(ManaCost.parse("{2}{U}")))
        override val triggeredAbilities =
            persistentListOf(
                // CR 603.6a: "When this creature enters, draw two cards." Fires on every entry, however
                // the Mulldrifter got there — cast, evoked, or returned from a graveyard.
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    effect =
                        ResolutionEffect { state, context ->
                            drawCards(state, context.controller, MULLDRIFTER_DRAW)
                        },
                ),
                // CR 702.74a: the evoke half — "if its evoke cost was paid, sacrifice it". CR 603.4 gates
                // the firing, so this ability simply does not exist for a hard-cast Mulldrifter.
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    interveningIf = InterveningIf.SourceWasEvoked,
                    effect =
                        ResolutionEffect { state, context ->
                            val self =
                                context.source
                                    ?: error(
                                        "CR 113.7c: Mulldrifter's evoke sacrifice needs its own source object",
                                    )
                            sacrificePermanent(state, self)
                        },
                ),
            )
    }

/**
 * Winding Way — `{1}{G}` Sorcery. "Choose creature or land. Reveal the top four cards of your library.
 * Put all cards of the chosen type revealed this way into your hand and the rest into your graveyard."
 *
 * **The card-selection family's last absentee, and the blocker was never `FW-MODAL`.** CardSelection.kt
 * recorded the diagnosis and it was right: modality had already landed and does not carry this card,
 * because a [dev.mtgplay.core.definition.SpellMode] is chosen at CR 601.2b — *while the spell is being
 * cast* — and Winding Way's choice belongs to its resolution (CR 608.2a). The card prints no
 * "Choose one —" bullet and no "as you cast". What it needed was a resolution-time type choice, which is
 * [ChosenTypeReveal].
 *
 * **The difference is a whole priority round, and it is visible in play.** Chosen as a mode, an opponent
 * holding an answer would know which half they were responding to; chosen as printed, they do not, and
 * the caster gets to watch the exchange before committing. Locking the choice in early would have
 * deleted that from the action space an agent trains against (ADR-005), which is the failure this engine
 * cares most about.
 *
 * **The choice is made blind.** "Choose creature or land" precedes "Reveal the top four cards" in the
 * printed order, so the four cards are revealed *after* the type is named; the engine pauses before
 * touching the library for exactly that reason.
 *
 * **Everything of the chosen type goes to the hand — this is not "up to".** That is why it is not
 * [dev.mtgplay.core.definition.LibraryReveal] with a large allowance: that clause is Malevolent Rumble's
 * "you *may* put a permanent card into your hand" and enumerates a keep-or-not choice per matching card.
 * Offering Winding Way's caster the option of keeping fewer would be an enumerated line the card forbids.
 *
 * **The rest goes to the *graveyard*, which is a cost and sometimes a benefit.** Naming creature buries
 * whatever lands the four turned up — and feeds a graveyard the gauntlet's recursion cares about
 * ([dreadReturn] is in the same pool). That is what makes the type choice a real decision rather than a
 * formality.
 *
 * A land *creature* would satisfy either choice: a card has a set of types, and "cards of the chosen
 * type" reads that set.
 */
val windingWay: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Winding Way",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None

        // CR 608.2c: the whole spell is the clause — there is no instruction before the choice.
        override val resolution = ResolutionEffect { state, _ -> state }
        override val chosenTypeReveal =
            ChosenTypeReveal(
                count = WINDING_WAY_REVEAL,
                choices = persistentListOf(RevealedCardFilter.CREATURE_CARD, RevealedCardFilter.LAND_CARD),
            )
    }

/**
 * Reckless Impulse — `{1}{R}` Sorcery. "Exile the top two cards of your library. Until the end of your
 * next turn, you may play those cards."
 *
 * **Two cards for two mana, with a deadline.** The cards are not drawn — they sit face up in exile,
 * visible to both players, and are lost if unplayed by the end of the caster's next turn. That deadline
 * is the card's whole cost, and modelling it as a draw would delete it.
 *
 * **Its permission cannot be a [CastingPermission], and that is why it needed a framework.** Every
 * member of that type is declared *by the card being cast* about itself — flashback, evoke, plot. This
 * permission is granted by Reckless Impulse's resolution to whatever two cards happened to be on top of
 * a library, so the card being played carries no declaration to read. The permission therefore rides on
 * the exiled **object** ([dev.mtgplay.core.state.GameObject.playGrantedTurn]) and the engine enumerates
 * it from exile at a priority window.
 *
 * **"Play", not "cast", and the difference is a land.** A land is never cast (CR 305.1), so a
 * permission that only reached the cast pipeline would silently drop every land off the top — on a
 * two-card exile, usually the single most likely outcome. Both halves are enumerated: a normal-cost cast
 * from exile for a spell, and the CR 116.2a play-land special action from exile for a land, still
 * bounded by the one-land-per-turn rule (CR 305.2).
 *
 * **The cards are played at their printed cost.** Nothing here is an alternative cost (CR 118.9): the
 * permission changes *where* a card may be played from, not what it costs — a `{4}{U}` card exiled this
 * way still costs `{4}{U}`, and the two exiled cards very often go unplayed for exactly that reason.
 *
 * **"Your *next* turn" is not "this turn".** Cast in its controller's main phase on turn N, the two
 * cards survive turn N's cleanup and the opponent's turn, and are lost at the cleanup of turn N+2 — so
 * a Reckless Impulse cast late in a turn still gets a full turn's use out of its cards. The engine
 * records when the permission was *granted* rather than when it ends, which is what lets it decide this
 * without predicting the turn order (see [dev.mtgplay.core.state.GameObject.playGrantedTurn]).
 */
val recklessImpulse: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Reckless Impulse",
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
                exileTopCardsPlayableUntilEndOfYourNextTurn(state, context.controller, RECKLESS_IMPULSE_EXILE)
            }
    }
