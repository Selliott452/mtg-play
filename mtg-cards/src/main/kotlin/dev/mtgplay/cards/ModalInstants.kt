package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ModalSpell
import dev.mtgplay.core.definition.ModeChoice
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
import dev.mtgplay.rules.effect.returnToOwnersHand
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
 *
 * ## `W9-B` — modal arity above one, and Call Damage Control
 *
 * [callDamageControl] is the pool's first card that does **not** print "Choose one —", and raising the
 * arity was the last thing standing between this engine and every "choose two" charm. Three pieces
 * landed for it: a count on the declaration ([dev.mtgplay.core.definition.ModeChoice]), a mode decision
 * that is a subset rather than a pick (`DecisionRequest.ChooseModes` is a `RangedSelection` now, `1..1`
 * for the four Blasts), and — the real work — **one targeting line per chosen mode**, carried through
 * gathering, the CR 601.2c re-validation, the CR 608.2b fizzle and the resolution as a per-mode split
 * rather than a flat list.
 *
 * **A correction to the brief, because it is what made the design tractable.** The packet was told to
 * apply "the CR 601.2c same-object rule **across** modes — you may not choose the same object as the
 * target of two different modes." The repo's own CR text says the opposite, and the rule is **CR 115.3**:
 *
 * > The same target can't be chosen multiple times for any one instance of the word "target" on a spell
 * > or ability. **If the spell or ability uses the word "target" in multiple places, the same object or
 * > player can be chosen once for each instance of the word "target"** (as long as it fits the targeting
 * > criteria).
 *
 * Each bullet is its own instance, so cross-mode duplication is *permitted*. Had the brief been right,
 * a set of modes would only be legal when a **system of distinct representatives** existed across the
 * modes' option lists — a bipartite matching evaluated at CR 601.2b, before any target is chosen — and
 * the mode decision would have had to enumerate whole *combinations* rather than modes. As the rule
 * actually reads, each mode is choosable on its own and no combination can be jointly unsatisfiable,
 * which is exactly why `castableModes` asking each mode about itself remains the right gate.
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

/** The modes Call Damage Control chooses from (CR 700.2a) — "Choose up to two." */
private const val CALL_DAMAGE_CONTROL_MODES: Int = 2

/**
 * Call Damage Control — `{1}{G}` Sorcery. "Choose up to two. Return those cards from your graveyard to
 * your hand. • Target artifact card. • Target creature card. • Target enchantment card. • Target land
 * card."
 *
 * **The pool's first card whose mode arity is not one**, and the card the `W9-B` framework exists for.
 * Its four bullets share a verb and differ only in the card type they name, which makes it the cleanest
 * possible demonstration of what modality above arity one actually costs: the *return* is trivial and
 * the *targeting* is the whole framework.
 *
 * **Choosing zero modes is legal, and the spell still resolves.** [ModeChoice.upTo] has minimum zero, so
 * Call Damage Control is castable with an empty graveyard — it resolves, does nothing, and goes to the
 * graveyard. That is a real line (binning a dead card to bait a counter or to fuel a later graveyard
 * effect), and the engine keeps it enumerable rather than declaring the cast illegal (ADR-005). It is
 * also why [someModeIsCastable] has an "up to" arm at all.
 *
 * **Two chosen modes are two independent target choices** (CR 115.3), not one choice of two cards, and
 * the difference is observable. An **artifact creature card** in the graveyard — Grixis Affinity fills a
 * graveyard with them — satisfies both the artifact bullet and the creature bullet, and CR 115.3
 * explicitly permits naming it for *each* instance of the word "target". Naming it twice returns it once
 * and wastes the second mode, which is legal and occasionally the only thing on offer. The flattening
 * this card invites — "return up to two target cards of different types" — cannot express that at all,
 * which is why the card waited for the framework instead of being approximated.
 *
 * **A dead bullet does not fizzle the spell** (CR 608.2b): with an artifact and no enchantment in the
 * graveyard, choosing both those modes is legal, and if the enchantment leaves in response the spell
 * still resolves and returns the artifact. Only *every* line being dead removes it from the stack — the
 * `all` in `fizzleSpell`, and the difference between returning one card and returning none.
 *
 * The scope is [GraveyardScope.YOURS] on every bullet: the printed line is "from **your** graveyard", so
 * an opponent's graveyard is never enumerated and this is not graveyard hate wearing a green coat.
 */
val callDamageControl: ModalSpell =
    object : ModalSpell {
        override val characteristics =
            PrintedCharacteristics(
                name = "Call Damage Control",
                manaCost = ManaCost.parse("{1}{G}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )

        override val timing = TimingClass.SORCERY_SPEED

        // CR 700.2a: "Choose up to two." Zero, one and two are all legal answers.
        override val modeChoice = ModeChoice.upTo(CALL_DAMAGE_CONTROL_MODES)
        override val modes =
            persistentListOf(
                graveyardReturnMode("artifact", GraveyardCardRestriction.ARTIFACT),
                graveyardReturnMode("creature", GraveyardCardRestriction.CREATURE),
                graveyardReturnMode("enchantment", GraveyardCardRestriction.ENCHANTMENT),
                graveyardReturnMode("land", GraveyardCardRestriction.LAND),
            )
    }

/**
 * One of Call Damage Control's four bullets: "Target [noun] card", returned to its owner's hand
 * (CR 400.7). Built by a helper rather than written out four times because the four differ in exactly
 * one value — and writing them out would invite the four to drift apart, which is the failure a card
 * with four near-identical modes is most exposed to.
 */
private fun graveyardReturnMode(
    noun: String,
    restriction: GraveyardCardRestriction,
): SpellMode =
    SpellMode(
        text = "Target $noun card.",
        targetSpec =
            TargetSpec.CardInGraveyard(
                restriction = restriction,
                // CR 404: "from **your** graveyard" — an opponent's is never offered.
                scope = GraveyardScope.YOURS,
            ),
        resolution =
            ResolutionEffect { state, context ->
                // CR 608.2b: a mode whose target has since left the graveyard simply does nothing; the
                // primitive is honest about a missing object rather than failing (see ReturnToHand.kt).
                returnToOwnersHand(state, targetedGraveyardCardId(context.targets))
            },
    )

/**
 * The one graveyard card a Call Damage Control bullet named (CR 115.1b), or `null` when the bullet named
 * nothing.
 *
 * **`null` is unreachable for this card and the signature admits it anyway**, because every mode here
 * carries [dev.mtgplay.core.definition.TargetCount.ONE] and a mode with no legal target is never offered
 * — so a chosen mode always holds exactly one target. What is *not* unreachable is the target having
 * left the graveyard since (CR 400.7), and that is handled downstream by the primitive rather than here.
 */
private fun targetedGraveyardCardId(targets: List<Target>): ObjectId {
    val target = targets.singleOrNull()
    require(target is Target.CardInGraveyard) {
        "CR 115.1b: Call Damage Control's mode targets exactly one graveyard card, got $targets"
    }
    return target.id
}
