package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.dealDamageToEachOpponent
import dev.mtgplay.rules.effect.dealDamageToEachPermanent
import dev.mtgplay.rules.effect.isCreaturePermanent
import kotlinx.collections.immutable.persistentSetOf

/*
 * The gauntlet's damage-dealing commons that need no framework the engine lacks (docs/decklists.md):
 * the Phyrexian one-drop [gutShot] (Mono-Blue Terror), the metalcraft burn [galvanicBlast] (Grixis
 * Affinity, Mono Red Rally), and the two sweepers [breathWeapon] (Gates, Grixis Affinity, GW Bogles,
 * Jund Wildfire, Monster Tron) and [endTheFestivities] (Mono Red Rally).
 *
 * Two of the four are more than a [dealDamage] call, and both stay card data rather than becoming
 * rules special cases (ADR-003):
 * - Galvanic Blast's metalcraft is a state-dependent *amount*, not a cost modification. A
 *   [ResolutionEffect] is already a pure function of the [GameState] (ADR-004), so counting the
 *   controller's artifacts as the spell resolves is exactly the [grabThePrize] precedent — a
 *   resolution that branches on what it reads — and needs nothing new;
 * - the two sweepers compose this packet's one new primitive,
 *   [dev.mtgplay.rules.effect.dealDamageToEachPermanent] (CR 120), whose affected-set predicate carries
 *   the printed qualifier ("non-Dragon", "they control"). The rules module supplies the one judgement
 *   the predicate cannot make for itself, [isCreaturePermanent] (CR 302.1).
 */

/** The damage a resolving Gut Shot deals to its target (CR 120.3a). */
const val GUT_SHOT_DAMAGE: Int = 1

/** The damage a resolving Galvanic Blast deals without metalcraft (CR 120.3a). */
const val GALVANIC_BLAST_DAMAGE: Int = 2

/** The damage a resolving Galvanic Blast deals instead when metalcraft is active (CR 120.3a). */
const val GALVANIC_BLAST_METALCRAFT_DAMAGE: Int = 4

/** How many artifacts their controller must control for metalcraft to be active — "three or more". */
const val METALCRAFT_ARTIFACT_COUNT: Int = 3

/** The damage a resolving Breath Weapon deals to each non-Dragon creature (CR 120.3d). */
const val BREATH_WEAPON_DAMAGE: Int = 2

/** The damage a resolving End the Festivities deals to each of its recipients (CR 120.3a, CR 120.3d). */
const val END_THE_FESTIVITIES_DAMAGE: Int = 1

/** The creature type Breath Weapon spares (CR 205.3m). */
private val DRAGON: Subtype = Subtype("Dragon")

/** Whether the battlefield object [obj] has printed card type [type] (CR 205.2); an inert object has none. */
private fun hasCardType(
    state: GameState,
    obj: GameObject,
    type: CardType,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.cardTypes
        ?.contains(type) == true

/** Whether the battlefield object [obj] has printed creature type [subtype] (CR 205.3); inert objects have none. */
private fun hasSubtype(
    state: GameState,
    obj: GameObject,
    subtype: Subtype,
): Boolean =
    state.definitions[obj.card]
        ?.characteristics
        ?.subtypes
        ?.contains(subtype) == true

/**
 * Gut Shot — `{R/P}` Instant. "({R/P} can be paid with either {R} or 2 life.) Gut Shot deals 1 damage
 * to any target." A Lightning Bolt in shape — one any-target damage instruction (CR 115.4) dealing
 * [GUT_SHOT_DAMAGE] on resolution (CR 120.3a) — and the pool's first card whose *whole cost* is a
 * Phyrexian symbol (CR 107.4).
 *
 * The reminder text is not a second ability: `{R/P}` is one symbol of the printed mana cost, payable
 * with red mana or with 2 life, and payment enumeration already offers both plans side by side
 * (docs/design/mana-payment.md). Life paid to a Phyrexian symbol is a *cost*, not damage (CR 118.4,
 * CR 119.3c), so paying it never triggers anything that watches damage — which is why the engine keeps
 * [dev.mtgplay.rules.effect.loseLife] distinct from [dealDamage].
 */
val gutShot: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Gut Shot",
                manaCost = ManaCost.parse("{R/P}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamage(state, context.damageSource(), context.targets.single(), GUT_SHOT_DAMAGE)
            }
    }

/**
 * Galvanic Blast — `{R}` Instant. "Galvanic Blast deals 2 damage to any target. Metalcraft — Galvanic
 * Blast deals 4 damage instead if you control three or more artifacts." One any-target damage
 * instruction (CR 115.4) whose *amount* depends on the game state at resolution.
 *
 * "Metalcraft" is an **ability word** (CR 207.2c): italic flavour that grants nothing and has no rules
 * meaning of its own — the whole ability is the sentence after it. It is therefore **not** a cost
 * concern and needs no framework: the count is taken as the spell resolves
 * (CR 608.2), from the [GameState] the [ResolutionEffect] is handed. Casting the spell with three
 * artifacts and then losing one before it resolves deals 2, not 4 — and the reverse holds too.
 * "You control" is ownership in the MVP pool; "artifact" is the printed card type (CR 205.2), so an
 * artifact *creature* and an artifact land both count.
 */
val galvanicBlast: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Galvanic Blast",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.AnyTarget
        override val resolution =
            ResolutionEffect { state, context ->
                val artifacts =
                    state.sharedZones.battlefield.count {
                        it.owner == context.controller && hasCardType(state, it, CardType.ARTIFACT)
                    }
                val damage =
                    if (artifacts >= METALCRAFT_ARTIFACT_COUNT) {
                        GALVANIC_BLAST_METALCRAFT_DAMAGE
                    } else {
                        GALVANIC_BLAST_DAMAGE
                    }
                dealDamage(state, context.damageSource(), context.targets.single(), damage)
            }
    }

/**
 * Breath Weapon — `{2}{R}` Instant. "Breath Weapon deals 2 damage to each non-Dragon creature." The
 * gauntlet's most-played sweeper: an untargeted (CR 115.1 — "each" is not "target") instant whose
 * affected set is every creature on the battlefield, **under either controller**, that is not a Dragon
 * (CR 205.3m). Its own controller's creatures are not spared; that symmetry is the card.
 *
 * Composes [dealDamageToEachPermanent] with the printed qualifier as its predicate: the damage is
 * *marked* on each creature (CR 120.3d) and nothing dies during resolution — the lethal-damage
 * state-based action (CR 704.5g) acts at the next check, once the spell has finished.
 */
val breathWeapon: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Breath Weapon",
                manaCost = ManaCost.parse("{2}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.INSTANT),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.INSTANT_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                dealDamageToEachPermanent(state, context.damageSource(), BREATH_WEAPON_DAMAGE) { current, obj ->
                    isCreaturePermanent(current, obj) && !hasSubtype(current, obj, DRAGON)
                }
            }
    }

/**
 * End the Festivities — `{R}` Sorcery. "End the Festivities deals 1 damage to each opponent and each
 * creature and planeswalker they control." A one-sided sweeper: one source, three kinds of recipient,
 * all dealt [END_THE_FESTIVITIES_DAMAGE] at once (CR 120.6).
 *
 * Two clauses over two primitives, and neither touches the caster's own board: the opponents take the
 * damage as life loss (CR 120.3a) through [dealDamageToEachOpponent], and their creatures and
 * planeswalkers take it as marked damage (CR 120.3d) through [dealDamageToEachPermanent] with the
 * printed "they control" qualifier (control is ownership in the MVP pool).
 *
 * **The planeswalker half is encoded, not dropped, but is unreachable today.** No planeswalker card is
 * Pauper-legal and the engine models no loyalty counters, so the affected set never contains one; the
 * predicate names the type anyway so the card is not quietly narrower than its oracle text. If a
 * planeswalker permanent ever reaches this engine, CR 120.3c (damage removes loyalty counters rather
 * than being marked) is a gap in [dealDamage], not here.
 */
val endTheFestivities: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "End the Festivities",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution =
            ResolutionEffect { state, context ->
                val burned =
                    dealDamageToEachOpponent(
                        state,
                        context.damageSource(),
                        context.controller,
                        END_THE_FESTIVITIES_DAMAGE,
                    )
                dealDamageToEachPermanent(burned, context.damageSource(), END_THE_FESTIVITIES_DAMAGE) { current, obj ->
                    obj.owner != context.controller &&
                        (
                            isCreaturePermanent(current, obj) ||
                                hasCardType(current, obj, CardType.PLANESWALKER)
                        )
                }
            }
    }
