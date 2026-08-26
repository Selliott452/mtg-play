package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.StaticContinuousEffect
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.Counter
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.powerOfChosenSource
import dev.mtgplay.rules.effect.putCountersIfAny
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * Spacecraft (CR 301.9, CR 702.180) — the gauntlet's one card of the type, and the mechanic the
 * deferred-ten list held back longest. `W10-C`.
 *
 * **Three printed things had to become expressible, and two of them were not what the recorded
 * diagnosis said.** `W8-G` filed the card as blocked on "a characteristic threshold keyed to a counter
 * count — a layer 4 type change *and* a layer 7b P/T setting, both conditional on state", and on
 * re-check after `FW-TYPECHANGE` and `FW-EQUIP` landed, exactly one third of that was right:
 *
 * 1. The **layer-4 type change** was real and is now expressible, but not the way that note assumed. It
 *    is a *static* ability of the permanent (CR 604.3), not an effect a resolving ability creates, and
 *    `StaticContinuousEffect` had no type field at all — `FW-TYPECHANGE` had put layer 4 on the *timed*
 *    generator alone and written down, in a note this packet got to keep its promise, exactly how the
 *    static half would arrive when a card needed it.
 * 2. The **layer-7b P/T setting was not needed and would have been wrong.** A Spacecraft prints its 7/7
 *    on the card (CR 208.1b); the numbers are there the whole time and only the *creature type* arrives
 *    at seven counters. What actually blocked the card was the opposite of a missing layer — it was
 *    `PrintedCharacteristics`' invariant asserting that a P/T box appears on creature cards *and only*
 *    creature cards, which is CR 208.1 without CR 208.1b's exception.
 * 3. The **conditional continuous effect** the packet brief asked about was never the open question it
 *    looked like. `FW-CONDSTATIC` built exactly it: a static ability carries a [StaticCondition] that is
 *    evaluated on every characteristic read, so applicability is re-derived continuously with no
 *    trigger, no stack, and nothing to invalidate (docs/design/layer-system.md §5). The layer store does
 *    **not** only hold effects created by a resolving ability — that is the *timed* store, and the
 *    static one has always been a live re-read. All this card needed was a condition shape that counts
 *    counters on the source rather than permanents on the board.
 *
 * The one genuinely new mechanism is the **cost**: "Tap another creature you control" is a cost with a
 * chosen object that neither `{T}` nor any existing component could express, and whose paid object the
 * resolution then has to read the power of. See [AbilityCost.TapPermanentYouControl] and
 * `AbilityTapCost.kt`.
 *
 * Oracle text re-read from the repo's Scryfall snapshot before any code was written. Two disagreements
 * with the packet brief are recorded on [pinnacleKillShip]: the brief omits the enters-the-battlefield
 * damage trigger entirely, and it omits the "7+ | Flying" line.
 */

/** The damage Pinnacle Kill-Ship's enters-the-battlefield trigger deals (CR 119.3). */
const val PINNACLE_KILL_SHIP_DAMAGE: Int = 10

/** The charge counters Pinnacle Kill-Ship needs before it is a creature and has flying (its "7+"). */
const val PINNACLE_KILL_SHIP_STATION_THRESHOLD: Int = 7

/**
 * The "7+" both of Pinnacle Kill-Ship's static abilities are conditioned on (CR 604.3, CR 122.6): seven
 * or more charge counters on the Spacecraft itself.
 *
 * One value shared by the two effects rather than written twice, because the printed threshold is one
 * number: a card whose type change and whose keyword grant could drift to different counts would be a
 * card that is a 7/7 non-flier or a flying noncreature, neither of which exists.
 */
private val stationThreshold: StaticCondition =
    StaticCondition.CountersOnSelf(
        counter = Counter.Charge,
        atLeast = PINNACLE_KILL_SHIP_STATION_THRESHOLD,
    )

/**
 * Pinnacle Kill-Ship — `{7}` Artifact — Spacecraft, printed 7/7.
 *
 * "When this Spacecraft enters, it deals 10 damage to up to one target creature." /
 * "Station (Tap another creature you control: Put charge counters equal to its power on this
 * Spacecraft. Station only as a sorcery. It's an artifact creature at 7+.)" / "7+ | Flying"
 *
 * **Two oracle disagreements with the packet brief, both flagged and both carried.** The brief describes
 * the card as an "artifact — Spacecraft 7/7 with Station" and nothing else. The printed card also has an
 * enters-the-battlefield trigger dealing 10 damage to up to one target creature, which is most of why
 * anyone plays it, and a **7+ | Flying** ability line, which is why the stationed body matters. Both
 * were free — [dealDamage] with [TargetCount.UpTo]`(1)`, and a second
 * [StaticContinuousEffect] sharing the first one's condition — so the card is carried whole rather than
 * as the two-thirds encoding the brief describes.
 *
 * **Station is four printed sentences and each one is a separate rules fact:**
 *
 * - *"Tap another creature you control"* — a cost with a chosen object
 *   ([AbilityCost.TapPermanentYouControl]). **Another** is load-bearing rather than flavour: a
 *   Kill-Ship at seven counters *is* a creature you control, so without the exclusion a finished
 *   Spacecraft could tap itself to station itself, which is an enumerated-but-illegal action (ADR-005)
 *   and one that appears only once the card starts working. Note also what the cost is *not* — there is
 *   no `{T}` and no mana, so a Kill-Ship may be stationed the turn it lands and stationed again every
 *   turn after.
 * - *"Put charge counters equal to its power"* — read on **resolution**, from the tapped creature's
 *   CR 613 layered power (CR 608.2h). Pumping the tapped creature in response really does add counters,
 *   and that is a line: tap a 1/1, respond with a pump spell, and the Spacecraft gains the pumped
 *   figure. The count may be zero — station a 0/1 mana dork and the ability resolves and does nothing
 *   (CR 122.1) — which is what [putCountersIfAny] exists for.
 * - *"Station only as a sorcery"* — [TimingClass.SORCERY_SPEED], CR 602.5d. Without it the ability would
 *   be an instant-speed trick that turns on a 7/7 flying blocker mid-combat, which the printed card does
 *   not permit.
 * - *"It's an artifact creature at 7+"* — a **static ability of the permanent** (CR 604.3) whose
 *   condition is the counter count, applied in CR 613 layer 4. Not a trigger and not a one-shot: remove
 *   a counter and the Spacecraft stops being a creature the instant it happens, with no player receiving
 *   priority in between, because characteristics are computed on read (docs/design/layer-system.md §5).
 *
 * **The printed 7/7 belongs to the card, not to a layer** (CR 208.1b), and getting that right is the
 * difference between this card and a plausible-looking wrong one. Encoding the body as a layer-7b
 * set-P/T that arrives with the type change would put the printed numbers in the rules text; encoding
 * the permanent as a creature from the start would let a bare Kill-Ship attack, block, and die to a
 * sweeper before it was ever stationed. Both fail in the direction ADR-005 cares about — an action the
 * engine offers that the rules do not.
 *
 * **The two static abilities share one condition and are still two effects**, matching the two printed
 * lines: the reminder text's type change and the separate "7+ | Flying" ability. Merging them into one
 * [StaticContinuousEffect] would give the same answer today and lose the correspondence to the card.
 */
val pinnacleKillShip: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Pinnacle Kill-Ship",
                manaCost = ManaCost.parse("{7}"),
                supertypes = persistentSetOf(),
                // CR 208.1b: a Spacecraft prints power and toughness while not being a creature; the
                // numbers exist from the moment it enters and go unused until layer 4 makes it one.
                cardTypes = persistentSetOf(CardType.ARTIFACT),
                subtypes = persistentSetOf(Subtype("Spacecraft")),
                powerToughness = PrintedPowerToughness(power = 7, toughness = 7),
            )

        // CR 301.1: the artifact spell itself is untargeted and sorcery-speed; the *trigger* targets.
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }

        override val triggeredAbilities =
            persistentListOf(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredBattlefieldSelf,
                    targetSpec =
                        TargetSpec.TargetPermanent(
                            restriction = PermanentRestriction.CREATURE,
                            count = TargetCount.UpTo(1),
                        ),
                    effect =
                        ResolutionEffect { state, context ->
                            // CR 115.1: "up to one" may be none, and a trigger that chose no target
                            // still resolves and simply does nothing (CR 608.2c).
                            val target = context.targets.singleOrNull() ?: return@ResolutionEffect state
                            dealDamage(state, context.damageSource(), target, PINNACLE_KILL_SHIP_DAMAGE)
                        },
                ),
            )

        override val activatedAbilities =
            persistentListOf(
                ActivatedAbility(
                    cost =
                        persistentListOf(
                            AbilityCost.TapPermanentYouControl(
                                filter = PermanentFilter(cardType = CardType.CREATURE, controlledByYou = true),
                                another = true,
                            ),
                        ),
                    // CR 602.5d: "Station only as a sorcery."
                    timing = TimingClass.SORCERY_SPEED,
                    effect =
                        ResolutionEffect { state, context ->
                            val tapped =
                                context.tappedForCost.singleOrNull()
                                    ?: error(
                                        "CR 602.2b: Station taps exactly one creature as its cost, but " +
                                            "the resolution was handed ${context.tappedForCost}",
                                    )
                            val spacecraft =
                                context.source
                                    ?: error("CR 602.2: Station puts counters on its own source, which is unknown")
                            // CR 608.2h: the tapped creature's power *now*, layered and live — a pump in
                            // response grows the counters. CR 113.7a answers if it has since left.
                            val power = powerOfChosenSource(state, ChosenPowerSource.ChosenCreature(tapped))
                            // CR 122.1: a 0/1 stationed for zero puts no counters and emits nothing.
                            putCountersIfAny(state, spacecraft, Counter.Charge, power)
                        },
                ),
            )

        override val staticContinuousEffects =
            persistentListOf(
                // CR 604.3 + CR 613.1d: "It's an artifact creature at 7+". A type *addition* — the
                // permanent stays an artifact and stays a Spacecraft (CR 205.1b).
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = stationThreshold,
                    addedCardTypes = persistentSetOf(CardType.CREATURE),
                ),
                // CR 604.3 + CR 613.1f: the separate printed "7+ | Flying" ability line, at the same
                // threshold. Two printed lines, two effects.
                StaticContinuousEffect(
                    affects = AffectedSet.Self,
                    condition = stationThreshold,
                    grantedKeywords = persistentSetOf(Keyword.FLYING),
                ),
            )
    }
