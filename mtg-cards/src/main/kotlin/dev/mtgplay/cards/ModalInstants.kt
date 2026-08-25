package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellMode
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.destroy
import dev.mtgplay.rules.effect.exileGraveyard
import dev.mtgplay.rules.effect.exilePermanent
import dev.mtgplay.rules.engine.countMatchingPermanents
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's **modal multi-target** instants: Cast into the Fire and Thraben Charm. Both were
 * written and dropped by `FW-MODAL` — MvpCards.kt records the reason verbatim, "both variable-count
 * multi-target lines (`FW-MULTITGT`), and a modal card whose modes cannot all be offered is an
 * enumeration gap rather than a partial card" — and both are here now that the count exists.
 *
 * They are kept apart from Blasts.kt deliberately. That file holds one *finding*: that the four colour
 * hosers are two templates and not one, split by where the colour test lives. These two share nothing
 * with it but the `Choose one —` header, and folding them in would bury that finding under unrelated
 * cards.
 *
 * **What each one adds beyond modality.** Cast into the Fire is the pool's first card whose *mode*
 * carries a count — [TargetCount.UpTo]`(2)` on one bullet and [TargetCount.ONE] on the other — which is
 * the shape docs/design/multi-target.md §8 named as the thing this framework deliberately did not model
 * and `FW-MODAL` then supplied from the other side: a mode is a whole targeting line, so a list of modes
 * *is* the list of targeting lines, each with its own count and its own CR 601.2c same-object scope.
 * Neither packet had to widen for the other; the two axes met.
 *
 * Thraben Charm adds the first **unbounded** count ([TargetCount.AnyNumber]) and the first count on a
 * *player* target at all. Its three modes are also the pool's widest spread of what a mode can be: a
 * board-counting damage effect, a plain destroy, and a whole-zone exile.
 *
 * Oracle text below is Scryfall's, fetched for this packet (`POST /cards/collection`, both found).
 */

/** The damage Cast into the Fire's first mode deals to each creature it names (CR 120). */
private const val CAST_INTO_THE_FIRE_DAMAGE: Int = 1

/** The creatures Cast into the Fire's first mode may name (CR 115.1). */
private const val CAST_INTO_THE_FIRE_TARGETS: Int = 2

/** The multiplier Thraben Charm's first mode applies to its creature count (CR 120). */
private const val THRABEN_CHARM_MULTIPLIER: Int = 2

/**
 * The battlefield permanents Thraben Charm's first mode counts: the creatures **you control**, not
 * every creature (CR 109.5). Against an empty board the mode deals zero damage, which CR 120.8 makes a
 * no-op rather than an error.
 */
private val CREATURES_YOU_CONTROL: PermanentFilter =
    PermanentFilter(cardType = CardType.CREATURE, controlledByYou = true)

/**
 * Cast into the Fire — `{1}{R}` Instant. "Choose one — • Cast into the Fire deals 1 damage to each of up
 * to two target creatures. • Exile target artifact."
 *
 * The card where modality and cardinality meet, and the first in the pool whose two modes differ in
 * **how many** things they target rather than only in what kind. Mode 0 is
 * [TargetCount.UpTo]`(2)` over [PermanentRestriction.CREATURE]; mode 1 is a single
 * [PermanentRestriction.ARTIFACT]. Each bullet is its own instance of the word "target" (CR 601.2c), so
 * each carries its own count and its own same-object scope — which is exactly the per-mode shape
 * docs/design/multi-target.md §8 said a future framework would need and this card is the first to want.
 *
 * **"Each of up to two target creatures", not "up to two damage."** One damage lands on *every* creature
 * named, so naming two creatures deals 1 to each and not 1 split between them. The fold over the target
 * list is the whole of that, and it is why the effect reads the list rather than `single()`.
 *
 * **Mode 0 is always castable, and mode 1 is not.** An "up to" minimum of zero passes the CR 601.2c
 * gate with an empty battlefield, so Cast into the Fire is *never* absent from the priority window on
 * mode 0's account — a real line in Magic, since binning a dead card to dig is sometimes right and the
 * cast still baits a counter. Mode 1's [TargetCount.ONE] is the ordinary rule: with no artifact anywhere
 * it is simply not offered, and the card is castable on one mode rather than two. That asymmetry inside
 * one card is what makes it worth a test.
 *
 * Its damage is [dealDamage] and so runs the CR 615 prevention step and marks damage rather than
 * destroying (CR 120.3d); a 1-toughness creature dies to the CR 704.5g state-based action afterwards,
 * not to this resolution.
 */
val castIntoTheFire: ModalSpell =
    object : ModalSpell {
        override val characteristics =
            PrintedCharacteristics(
                name = "Cast into the Fire",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text = "Cast into the Fire deals 1 damage to each of up to two target creatures.",
                    targetSpec =
                        TargetSpec.TargetPermanent(
                            restriction = PermanentRestriction.CREATURE,
                            count = TargetCount.UpTo(CAST_INTO_THE_FIRE_TARGETS),
                        ),
                    resolution =
                        ResolutionEffect { state, context ->
                            // CR 120: one damage to *each* named creature, not one shared between them.
                            context.targets.fold(state) { current, target ->
                                dealDamage(
                                    current,
                                    context.damageSource(),
                                    target,
                                    CAST_INTO_THE_FIRE_DAMAGE,
                                )
                            }
                        },
                ),
                SpellMode(
                    text = "Exile target artifact.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT),
                    resolution =
                        ResolutionEffect { state, context ->
                            exilePermanent(state, targetedPermanentId(context.targets, "Cast into the Fire"))
                        },
                ),
            )
    }

/**
 * Thraben Charm — `{1}{W}` Instant. "Choose one — • Thraben Charm deals damage equal to twice the number
 * of creatures you control to target creature. • Destroy target enchantment. • Exile any number of
 * target players' graveyards."
 *
 * The pool's first **three**-mode card, and the one that stretches what a mode can be furthest: a
 * board-counting damage effect, a plain destroy, and a whole-zone exile whose target is a *player*.
 *
 * **Mode 0's magnitude is counted on resolution** (CR 608.2), not on cast, so a creature that dies in
 * response shrinks the damage and one that enters in response grows it. That is [countMatchingPermanents]
 * over [CREATURES_YOU_CONTROL], the same accessor Skred and Timberwatch Elf use — and note the read
 * point is Skred's, not Timberwatch Elf's: damage is dealt once and has no duration, so there is nothing
 * to snapshot and the CR 608.2h/611.2d distinction that governs an until-end-of-turn pump does not
 * arise here. With no creatures the mode deals **zero** damage, which CR 120.8 makes a no-op — the mode
 * is still castable and still offered, because the count is not part of its targeting line.
 *
 * **Mode 1 is [PermanentRestriction.ENCHANTMENT]**, this packet's other new restriction, and an Aura is
 * an enchantment (CR 303.4). Against GW Bogles that is the whole point: a hexproof Slippery Bogle cannot
 * be targeted, but the Ethereal Armor on it is a separate permanent with its own qualities and is a
 * perfectly legal target.
 *
 * **Mode 2 is the pool's first unbounded count** ([TargetCount.AnyNumber]) and its first count on
 * [TargetSpec.TargetPlayer]. "Any number of target players" is not "up to two" with the number filled
 * in: the card prints no limit, and the only bound is how many players there are, which
 * `targetChoiceBounds` supplies by clamping the maximum to the option count
 * (docs/design/multi-target.md §4). Naming **zero** players is legal and the spell still resolves —
 * a minimum of zero puts this mode on the "up to" side of the CR 608.2b divider — and naming a player
 * whose graveyard is empty is legal too and simply does nothing.
 *
 * Naming *yourself* is legal and occasionally right, which is worth stating because the mode reads as
 * pure graveyard hate: [TargetSpec.TargetPlayer] enumerates every player including the chooser
 * (CR 115.1a), unlike [TargetSpec.TargetOpponent], and the card says "players" rather than "opponents".
 */
val thrabenCharm: ModalSpell =
    object : ModalSpell {
        override val characteristics =
            PrintedCharacteristics(
                name = "Thraben Charm",
                manaCost = ManaCost.parse("{1}{W}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.INSTANT_SPEED
        override val modes =
            persistentListOf(
                SpellMode(
                    text =
                        "Thraben Charm deals damage equal to twice the number of creatures you " +
                            "control to target creature.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.CREATURE),
                    resolution =
                        ResolutionEffect { state, context ->
                            // CR 608.2: the count is taken as the spell resolves, not as it was cast.
                            val creatures = countMatchingPermanents(state, CREATURES_YOU_CONTROL, context.controller)
                            dealDamage(
                                state,
                                context.damageSource(),
                                context.targets.single(),
                                creatures * THRABEN_CHARM_MULTIPLIER,
                            )
                        },
                ),
                SpellMode(
                    text = "Destroy target enchantment.",
                    targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.ENCHANTMENT),
                    resolution =
                        ResolutionEffect { state, context ->
                            destroy(state, targetedPermanentId(context.targets, "Thraben Charm"))
                        },
                ),
                SpellMode(
                    text = "Exile any number of target players' graveyards.",
                    targetSpec = TargetSpec.TargetPlayer(count = TargetCount.AnyNumber),
                    resolution =
                        ResolutionEffect { state, context ->
                            targetedPlayers(context.targets).fold(state, ::exileGraveyard)
                        },
                ),
            )
    }

/**
 * The one battlefield permanent a mode was told to act on (CR 115.1b), failing loudly on any other target
 * kind or arity: the CR 608.2b re-check has already run, so anything else here is an engine defect
 * (ADR-005).
 */
private fun targetedPermanentId(
    targets: List<Target>,
    cardName: String,
): ObjectId =
    (targets.singleOrNull() as? Target.Permanent)?.id
        ?: error("CR 115.1b: $cardName's mode targets exactly one permanent, got $targets")

/**
 * The players Thraben Charm's third mode was told to act on (CR 115.1a), in the order chosen.
 *
 * **An empty list is a correct input, not a defect**: "any number of target players" may legitimately
 * name none, and CR 608.2b lets the spell resolve anyway. What still fails loudly is a target of the
 * wrong kind (ADR-005).
 */
private fun targetedPlayers(targets: List<Target>): List<PlayerId> =
    targets.map { target ->
        (target as? Target.Player)?.id
            ?: error("CR 115.1a: Thraben Charm's third mode targets only players, got $target")
    }
