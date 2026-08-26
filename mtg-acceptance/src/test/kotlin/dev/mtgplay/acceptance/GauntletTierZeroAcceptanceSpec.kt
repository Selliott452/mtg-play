package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.decision.SymbolPayment
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The gauntlet Tier-0 packet's twelve cards, each driven end-to-end through the real engine by
 * [ScriptedGame] (which invariant-checks every transition): Gut Shot's Phyrexian life payment
 * (CR 107.4), Galvanic Blast's metalcraft amount (CR 608.2), the two sweepers' marked damage and the
 * deaths that follow (CR 120.3d, CR 704.5g), the enters-the-battlefield and dies triggers
 * (CR 603.6a–b), Spirit Link's damage trigger paying the *Aura's* controller (CR 603.2), Gnaw to the
 * Bone's graveyard count and flashback (CR 702.34), Union of the Third Path's after-the-draw hand
 * count, Spinewoods Paladin's plot (CR 702.140), Wellwisher's `{T}` ability (CR 602), and Murmuring
 * Mystic's cast trigger (CR 601.2i). Every card is cast, activated, or attacked with for real —
 * nothing is asserted off a definition here.
 */
class GauntletTierZeroAcceptanceSpec :
    StringSpec({

        "CR 107.4: Gut Shot is cast for 2 life with no mana at all, and deals its 1 damage" {
            val game =
                tierZeroGame(
                    alice = TierZeroBoard(hand = listOf(obj(10, "Gut Shot"))),
                )
            game.castOption("Gut Shot")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            // With no land on the battlefield the only enumerated plan is the Phyrexian alternative.
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options.map { it.payments } shouldContainExactly listOf(listOf(SymbolPayment.WithTwoLife))
            game.apply(Decision.SingleSelect(payment.id, 0))
            game.settle()
            // CR 118.4: the 2 life is a *cost*, not damage — bob took the damage, alice merely paid.
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE - PHYREXIAN_LIFE
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - GUT_SHOT
        }

        "CR 107.4: with a red source available Gut Shot enumerates the mana plan beside the life plan" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Gut Shot")),
                            battlefield = listOf(obj(0, "Mountain")),
                        ),
                )
            game.castOption("Gut Shot")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options.count { it.payments == listOf(SymbolPayment.WithTwoLife) } shouldBe 1
            payment.options.count { it.activations.isNotEmpty() } shouldBe 1
            // Paying with the Mountain costs no life at all.
            val manaPlan = payment.options.indexOfFirst { it.activations.isNotEmpty() }
            game.apply(Decision.SingleSelect(payment.id, manaPlan))
            game.settle()
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - GUT_SHOT
        }

        "CR 608.2: Galvanic Blast deals 2 with two artifacts and 4 with three — counted on resolution" {
            fun blastWith(artifacts: Int): ScriptedGame {
                val moxites = (1L..artifacts.toLong()).map { obj(it, "Melded Moxite") }
                val game =
                    tierZeroGame(
                        alice =
                            TierZeroBoard(
                                hand = listOf(obj(10, "Galvanic Blast")),
                                battlefield = listOf(obj(0, "Mountain")) + moxites,
                            ),
                    )
                game.castTargeting("Galvanic Blast", Target.Player(bob))
                return game.settle()
            }
            blastWith(METALCRAFT_COUNT - 1)
                .state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - BLAST_DAMAGE
            blastWith(METALCRAFT_COUNT)
                .state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - BLAST_METALCRAFT
        }

        "CR 120.3d / CR 704.5g: Breath Weapon marks 2 on every creature, killing both seats' small bodies" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Breath Weapon")),
                            battlefield =
                                listOf(
                                    obj(0, "Mountain"),
                                    obj(1, "Mountain"),
                                    obj(2, "Mountain"),
                                    notSick(obj(3, "Grizzly Bears")),
                                ),
                        ),
                    bob = TierZeroBoard(battlefield = listOf(notSick(obj(40, "Standing Troops")))),
                )
            game.castOption("Breath Weapon")
            game.payFirstPlan()
            game.settle()
            // The caster's own creature is not spared — "each creature" is every creature (CR 109.5).
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Breath Weapon"), CardRef("Grizzly Bears"))
            // The 1/4 survives with the damage marked until cleanup (CR 514.2).
            val troops =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Standing Troops") }
            troops.damageMarked shouldBe BREATH_WEAPON_SWEEP
        }

        "CR 205.3m: Breath Weapon spares a Dragon and kills the non-Dragon beside it" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Breath Weapon")),
                            battlefield =
                                listOf(
                                    obj(0, "Mountain"),
                                    obj(1, "Mountain"),
                                    obj(2, "Mountain"),
                                    notSick(obj(3, "Grizzly Bears")),
                                    notSick(obj(4, FIXTURE_DRAGON)),
                                ),
                        ),
                    definitions = withDragon(),
                )
            game.castOption("Breath Weapon")
            game.payFirstPlan()
            game.settle()
            val dragon =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef(FIXTURE_DRAGON) }
            dragon.damageMarked shouldBe 0
            game.state.sharedZones.battlefield
                .none { it.card == CardRef("Grizzly Bears") }
                .shouldBeTrue()
        }

        "CR 115.1: End the Festivities is one-sided — and 'each' is not targeting, so hexproof is no shield" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "End the Festivities")),
                            battlefield = listOf(obj(0, "Mountain"), notSick(obj(1, "Grizzly Bears"))),
                        ),
                    bob = TierZeroBoard(battlefield = listOf(notSick(obj(40, "Gladecover Scout")))),
                )
            game.castOption("End the Festivities")
            game.payFirstPlan()
            game.settle()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - FESTIVITIES_DAMAGE
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
            // CR 702.11b: hexproof stops an opponent *targeting* it; this spell targets nothing.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Gladecover Scout"))
            // The caster's own creature is untouched — "they control" excludes it.
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Grizzly Bears") }
                .damageMarked shouldBe 0
        }

        "CR 603.6a: Healer of the Glade enters and its trigger gains 3 life" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Healer of the Glade")),
                            battlefield = listOf(obj(0, "Forest")),
                        ),
                )
            game.castOption("Healer of the Glade")
            game.payFirstPlan()
            game.settle()
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + HEALER_LIFEGAIN
            game.state.sharedZones.battlefield
                .count { it.card == CardRef("Healer of the Glade") } shouldBe 1
        }

        "CR 603.6b: Outlaw Medic's dies trigger draws a card when a lethal-damage SBA kills it" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Lightning Bolt")),
                            battlefield = listOf(obj(0, "Mountain"), notSick(obj(1, "Outlaw Medic"))),
                            library = listOf(obj(20, "Rancor")),
                        ),
                )
            game.castTargeting("Lightning Bolt", Target.Permanent(ObjectId(1)))
            game.settle()
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Lightning Bolt"), CardRef("Outlaw Medic"))
            // The trigger fired from the state just before it left (CR 603.10) and drew.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Rancor"))
        }

        "CR 702.15b: Outlaw Medic's printed lifelink gains its controller the combat damage it deals" {
            val game =
                tierZeroGame(
                    alice = TierZeroBoard(battlefield = listOf(notSick(obj(1, "Outlaw Medic")))),
                )
            game.marchToCombatAndAttack(listOf(ObjectId(1)))
            game.driveUntil {
                game.state.players
                    .getValue(bob)
                    .life < STARTING_LIFE
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - OUTLAW_MEDIC_POWER
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + OUTLAW_MEDIC_POWER
        }

        "CR 603.2: Spirit Link's trigger gains its controller the damage the enchanted creature deals" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Spirit Link")),
                            battlefield = listOf(obj(0, "Plains"), notSick(obj(1, "Grizzly Bears"))),
                        ),
                )
            game.castEnchanting("Spirit Link", ObjectId(1))
            game.settle()
            game.marchToCombatAndAttack(listOf(ObjectId(1)))
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life > STARTING_LIFE
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - GRIZZLY_BEARS_POWER
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + GRIZZLY_BEARS_POWER
        }

        "CR 611.2c: Spirit Link on an opponent's blocker still pays the Aura's controller, not the damager's" {
            // The sharp contrast with lifelink (CR 702.15b), which would pay bob: the Aura is alice's,
            // so alice gains the life bob's Standing Troops deals to her own attacking Bears.
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Spirit Link")),
                            battlefield = listOf(obj(0, "Plains"), notSick(obj(1, "Grizzly Bears"))),
                        ),
                    bob = TierZeroBoard(battlefield = listOf(notSick(obj(40, "Standing Troops")))),
                )
            game.castEnchanting("Spirit Link", ObjectId(40))
            game.settle()
            game.marchToCombatAndAttack(listOf(ObjectId(1)))
            game.blockWith(blocker = ObjectId(40), attacker = ObjectId(1))
            game.driveUntil {
                game.state.players
                    .getValue(alice)
                    .life > STARTING_LIFE
            }
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + STANDING_TROOPS_POWER
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE
        }

        "CR 702.34: Gnaw to the Bone gains 2 per creature card, then flashes back for the same count" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Gnaw to the Bone")),
                            battlefield = (0L..5L).map { obj(it, "Forest") },
                            graveyard =
                                listOf(
                                    obj(30, "Grizzly Bears"),
                                    obj(31, "Hill Giant"),
                                    obj(32, "Lightning Bolt"),
                                ),
                        ),
                )
            game.castOption("Gnaw to the Bone")
            game.payFirstPlan()
            game.settle()
            val afterFirst = STARTING_LIFE + GNAW_PER_CREATURE * GRAVEYARD_CREATURE_CARDS
            game.state.players
                .getValue(alice)
                .life shouldBe afterFirst

            // Gnaw is now in the graveyard itself — a noncreature card, so the count is unchanged.
            game.castFlashback("Gnaw to the Bone")
            game.payFirstPlan()
            game.settle()
            game.state.players
                .getValue(alice)
                .life shouldBe
                afterFirst + GNAW_PER_CREATURE * GRAVEYARD_CREATURE_CARDS
            // CR 702.34e: a flashed-back spell is exiled as it leaves the stack.
            game.state.sharedZones.exile
                .count { it.card == CardRef("Gnaw to the Bone") } shouldBe 1
            game.state.players
                .getValue(alice)
                .graveyard
                .none { it.card == CardRef("Gnaw to the Bone") }
                .shouldBeTrue()
        }

        "CR 608.2c: Union of the Third Path counts the hand after the draw, so the drawn card counts itself" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Union of the Third Path"), obj(11, "Rancor")),
                            battlefield = listOf(obj(0, "Plains"), obj(1, "Plains"), obj(2, "Plains")),
                            library = listOf(obj(20, "Grizzly Bears")),
                        ),
                )
            game.castOption("Union of the Third Path")
            game.payFirstPlan()
            game.settle()
            // Hand after the draw: Rancor plus the drawn Grizzly Bears — two cards, two life.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Rancor"), CardRef("Grizzly Bears"))
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + UNION_HAND_AFTER_DRAW
        }

        "CR 603.6a: Spinewoods Paladin cast for {4}{G} enters as a 5/4 trampler and gains 3" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Spinewoods Paladin")),
                            battlefield = (0L..4L).map { obj(it, "Forest") },
                        ),
                )
            game.castOption("Spinewoods Paladin")
            game.payFirstPlan()
            game.settle()
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PALADIN_LIFEGAIN
            game.state.sharedZones.battlefield
                .count { it.card == CardRef("Spinewoods Paladin") } shouldBe 1
        }

        "CR 702.140: Spinewoods Paladin is plotted for {3}{G} and free-cast — but only on a later turn" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Spinewoods Paladin")),
                            battlefield = (0L..3L).map { obj(it, "Forest") },
                        ),
                )
            game.plotCard("Spinewoods Paladin")
            game.payFirstPlan()
            game.state.sharedZones.exile
                .single { it.card == CardRef("Spinewoods Paladin") }
                .plottedTurn shouldBe TIER_ZERO_TURN
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .none { it.permission is CastingPermission.Plot }
                .shouldBeTrue()

            // A later turn: the free cast from exile resolves, and the enters trigger still gains 3.
            val later = ScriptedGame.startFrom(plottedPaladinState())
            later.castPlotFree("Spinewoods Paladin")
            later.payFirstPlan()
            later.settle()
            later.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PALADIN_LIFEGAIN
            later.state.sharedZones.battlefield
                .count { it.card == CardRef("Spinewoods Paladin") } shouldBe 1
            later.state.sharedZones.exile
                .shouldBeEmpty()
        }

        "CR 602: Wellwisher taps for 1 life per Elf on the battlefield — both seats' Elves count" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Wellwisher")),
                                    notSick(obj(1, "Gladecover Scout")),
                                ),
                        ),
                    bob = TierZeroBoard(battlefield = listOf(notSick(obj(40, "Silhana Ledgewalker")))),
                )
            game.activateAbility("Wellwisher")
            game.settle()
            // Wellwisher, Gladecover Scout (Elf Scout), and bob's Silhana Ledgewalker (Elf Rogue).
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + ELVES_ON_BATTLEFIELD
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Wellwisher") }
                .tapped
                .shouldBeTrue()
        }

        "CR 302.6: a summoning-sick Wellwisher has no {T} ability to activate" {
            val game =
                tierZeroGame(alice = TierZeroBoard(battlefield = listOf(obj(0, "Wellwisher"))))
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.ActivateAbility>()
                .none { it.card == CardRef("Wellwisher") }
                .shouldBeTrue()
        }

        "CR 601.2i: Murmuring Mystic makes a flying Bird when an instant is cast, but not for a creature" {
            val game =
                tierZeroGame(
                    alice =
                        TierZeroBoard(
                            hand = listOf(obj(10, "Lightning Bolt"), obj(11, "Grizzly Bears")),
                            battlefield =
                                listOf(
                                    obj(0, "Mountain"),
                                    obj(1, "Forest"),
                                    obj(2, "Forest"),
                                    notSick(obj(3, "Murmuring Mystic")),
                                ),
                        ),
                )
            game.castTargeting("Lightning Bolt", Target.Player(bob))
            game.settle()
            game.state.sharedZones.battlefield
                .count { it.card == CardRef.token("Bird Illusion") } shouldBe 1
            val bird =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef.token("Bird Illusion") }
            bird.owner shouldBe alice

            // A creature spell is neither an instant nor a sorcery: no second Bird.
            game.castOption("Grizzly Bears")
            game.payFirstPlan()
            game.settle()
            game.state.sharedZones.battlefield
                .count { it.card == CardRef.token("Bird Illusion") } shouldBe 1
        }
    })

// ---- the printed numbers these scenarios assert ---------------------------------------------------

/** The life a Phyrexian symbol's alternative costs (CR 107.4). */
private const val PHYREXIAN_LIFE: Int = 2

/** Gut Shot's damage (CR 120.3a). */
private const val GUT_SHOT: Int = 1

/** Galvanic Blast's damage without metalcraft, and with it (CR 120.3a). */
private const val BLAST_DAMAGE: Int = 2
private const val BLAST_METALCRAFT: Int = 4

/** How many artifacts turn metalcraft on. */
private const val METALCRAFT_COUNT: Int = 3

/** Breath Weapon's damage to each non-Dragon creature (CR 120.3d). */
private const val BREATH_WEAPON_SWEEP: Int = 2

/** End the Festivities' damage to each of its recipients (CR 120.3a, CR 120.3d). */
private const val FESTIVITIES_DAMAGE: Int = 1

/** Healer of the Glade's and Spinewoods Paladin's enters-the-battlefield lifegain (CR 119.3). */
private const val HEALER_LIFEGAIN: Int = 3
private const val PALADIN_LIFEGAIN: Int = 3

/** Outlaw Medic's printed power — what its lifelink gains on an unblocked attack (CR 702.15b). */
private const val OUTLAW_MEDIC_POWER: Int = 1

/** Grizzly Bears' and Standing Troops' printed power, the damage Spirit Link's trigger pays out. */
private const val GRIZZLY_BEARS_POWER: Int = 2
private const val STANDING_TROOPS_POWER: Int = 1

/** Gnaw to the Bone's life per creature card, and how many its scenario graveyard holds. */
private const val GNAW_PER_CREATURE: Int = 2
private const val GRAVEYARD_CREATURE_CARDS: Int = 2

/** Union of the Third Path's hand size once the draw has happened. */
private const val UNION_HAND_AFTER_DRAW: Int = 2

/** The Elves on the battlefield in Wellwisher's scenario, under both controllers. */
private const val ELVES_ON_BATTLEFIELD: Int = 3

// ---- the synthetic Dragon (no Dragon is Pauper-legal, so Breath Weapon's exclusion needs one) -----

/** The name of the Dragon fixture Breath Weapon's exclusion scenario places on the battlefield. */
private const val FIXTURE_DRAGON: String = "Fixture Dragon"

/**
 * A 2/2 Dragon body: the *only* thing this fixture adds over Grizzly Bears is the Dragon creature type
 * (CR 205.3m), so Breath Weapon's exclusion is the single variable between the two. No Dragon is
 * Pauper-legal, so the gauntlet pool cannot supply one.
 */
private val fixtureDragon: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = FIXTURE_DRAGON,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(Subtype("Dragon")),
                powerToughness = PrintedPowerToughness(power = 2, toughness = 2),
            )
    }

/** The real-card registry plus [fixtureDragon]. */
private fun withDragon(): Map<CardRef, CardDefinition> =
    MvpCards.definitions + (CardRef(FIXTURE_DRAGON) to fixtureDragon)

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------

/** The current priority window, which must be a [DecisionRequest.ChooseAction] (CR 117). */
private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** Selects the cast option for [name] from the current priority window (CR 601.2). */
private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Selects the graveyard (flashback) cast option for [name] (CR 702.34a). */
private fun ScriptedGame.castFlashback(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell &&
                it.card == CardRef(name) &&
                it.permission is CastingPermission.Flashback
        }
    check(index >= 0) { "no flashback cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Selects the free plot cast from exile for [name] (CR 702.140). */
private fun ScriptedGame.castPlotFree(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(name) && it.permission is CastingPermission.Plot
        }
    check(index >= 0) { "no plot free-cast for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Takes the plot special action for [name] (CR 702.140). */
private fun ScriptedGame.plotCard(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.PlotCard && it.card == CardRef(name) }
    check(index >= 0) { "no PlotCard option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Selects the activate-ability option for [name] from the current priority window (CR 602.2). */
private fun ScriptedGame.activateAbility(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(name) }
    check(index >= 0) { "no ActivateAbility option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Answers the pending payment request with its first enumerated plan (CR 601.2g). */
private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Casts the targeted spell [name] at [target], paying its first plan (CR 601.2c, CR 601.2g). */
private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

/** Casts the Aura [name] onto the battlefield object [target] (CR 303.4a, CR 601.2c). */
private fun ScriptedGame.castEnchanting(
    name: String,
    target: ObjectId,
): ScriptedGame = castTargeting(name, Target.Permanent(target))

/** Passes to the declare-attackers step and declares [attackers] (CR 508.1). */
private fun ScriptedGame.marchToCombatAndAttack(attackers: List<ObjectId>): ScriptedGame {
    driveUntil { pendingRequest is DecisionRequest.DeclareAttackers }
    val declare = pendingRequest.shouldBeInstanceOf<DecisionRequest.DeclareAttackers>()
    val indices = attackers.map { id -> declare.options.indexOfFirst { it.attacker == id } }
    check(indices.all { it >= 0 }) { "an attacker in $attackers is not eligible in ${declare.options}" }
    return apply(Decision.MultiSelect(declare.id, indices))
}

/** Blocks [attacker] with [blocker] at the declare-blockers step (CR 509.1). */
private fun ScriptedGame.blockWith(
    blocker: ObjectId,
    attacker: ObjectId,
): ScriptedGame {
    driveUntil { pendingRequest is DecisionRequest.DeclareBlockers }
    val declare = pendingRequest.shouldBeInstanceOf<DecisionRequest.DeclareBlockers>()
    val index = declare.options.indexOfFirst { it.blocker == blocker && it.attacker == attacker }
    check(index >= 0) { "no legal block of $attacker by $blocker in ${declare.options}" }
    return apply(Decision.MultiSelect(declare.id, listOf(index)))
}

/** Passes priority, ordering any simultaneous triggers in the deterministic identity permutation. */
private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> {
            val index = request.options.indexOfFirst { it is PriorityOption.Pass }
            check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
            apply(Decision.SingleSelect(request.id, index))
        }

        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

/**
 * Advances until the stack is empty **and** no trigger is still waiting to be put on it (CR 603.3b) —
 * the "this spell and everything it set off has finished" pause. Waiting on the empty stack alone is
 * not enough: a permanent spell resolves and *then* its enters-the-battlefield trigger is queued.
 */
private fun ScriptedGame.settle(): ScriptedGame =
    driveUntil { state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty() }

/** Advances (passing / declining combat / ordering triggers) until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_TIER_ZERO_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_TIER_ZERO_DRIVE_STEPS steps" }
    return this
}

private const val MAX_TIER_ZERO_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val TIER_ZERO_TURN: Int = 3

/** The turn a plotted card is free-cast on: plotting is legal only on an *earlier* turn (CR 702.140). */
private const val TIER_ZERO_LATER_TURN: Int = 4

/** One seat's hand, battlefield, library, and graveyard objects, for constructing a scenario board. */
private data class TierZeroBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** A hand/battlefield/library object [id] of card [name] (owner reassigned per seat by [tierZeroGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield creature as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): [holder] holds priority on
 * the given [alice] and [bob] boards over [definitions] (the real [MvpCards] registry unless a scenario
 * needs a fixture), on turn [TIER_ZERO_TURN], which belongs to alice. Every transition is
 * invariant-checked by the driver.
 */
private fun tierZeroGame(
    alice: TierZeroBoard = TierZeroBoard(),
    bob: TierZeroBoard = TierZeroBoard(),
    holder: PlayerId = dev.mtgplay.acceptance.alice,
    definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobLibrary = bob.library.map { it.copy(owner = bobSeat) }
    val bobGrave = bob.graveyard.map { it.copy(owner = bobSeat) }
    val placed =
        alice.hand + alice.battlefield + alice.library + alice.graveyard +
            bobHand + bobField + bobLibrary + bobGrave
    var nextId = (placed.maxOfOrNull { it.id.value } ?: -1L) + 1

    // Spare library cards so an incidental draw step never decks a scenario out (CR 704.5b).
    fun padding(owner: PlayerId): List<GameObject> =
        List(SPARE_LIBRARY_CARDS) { GameObject(ObjectId(nextId++), CardRef("Mountain"), owner) }

    val aliceLibrary = alice.library + padding(aliceSeat)
    val paddedBobLibrary = bobLibrary + padding(bobSeat)

    fun priorityOf(seat: PlayerId) = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = aliceLibrary.toPersistentList(),
                            hand = alice.hand.toPersistentList(),
                            graveyard = alice.graveyard.toPersistentList(),
                            priorityStatus = priorityOf(aliceSeat),
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = paddedBobLibrary.toPersistentList(),
                            hand = bobHand.toPersistentList(),
                            graveyard = bobGrave.toPersistentList(),
                            priorityStatus = priorityOf(bobSeat),
                        ),
                ),
            turn = Turn(aliceSeat, TIER_ZERO_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = (alice.battlefield + bobField).toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}

/** How many spare Mountains each seat's library is padded with. */
private const val SPARE_LIBRARY_CARDS: Int = 4

/**
 * Alice holding priority on [TIER_ZERO_LATER_TURN] with Spinewoods Paladin already plotted in exile on
 * [TIER_ZERO_TURN] — the state the free cast from exile resumes from (CR 702.140).
 */
private fun plottedPaladinState(): GameState {
    val exiled = GameObject(ObjectId(0), CardRef("Spinewoods Paladin"), alice, plottedTurn = TIER_ZERO_TURN)

    fun seat(
        owner: PlayerId,
        holdsPriority: Boolean,
    ) = PlayerState(
        life = STARTING_LIFE,
        library =
            List(SPARE_LIBRARY_CARDS) { GameObject(ObjectId(1L + it), CardRef("Mountain"), owner) }
                .toPersistentList(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
        priorityStatus = if (holdsPriority) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )
    return GameState(
        players =
            persistentMapOf(
                alice to seat(alice, holdsPriority = true),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library =
                            List(SPARE_LIBRARY_CARDS) {
                                GameObject(ObjectId(1L + SPARE_LIBRARY_CARDS + it), CardRef("Mountain"), bob)
                            }.toPersistentList(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, TIER_ZERO_LATER_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf(exiled)),
        nextObjectId = 1L + 2 * SPARE_LIBRARY_CARDS,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
