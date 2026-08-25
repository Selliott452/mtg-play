package dev.mtgplay.rules

import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Protection (CR 702.16), all four letters of DEBT, on the four seams the engine already had.
 *
 * The framework's shape is set by two facts. First, **protection is quality-relative, not
 * controller-relative**: every check needs the *other* object — the spell, the Aura, the blocker,
 * the damage source — and its characteristics, where hexproof needs only who is deciding. Second,
 * **the quality is not a colour enum** (CR 702.16a: "usually a color … but can be any characteristic
 * value"), so "Paladin" carries *monocolored* and the type that expresses it is sealed.
 *
 * `EnumerationProbe` structurally cannot catch a missed protection check: it detects a phantom option
 * by replaying it through `advance` and watching it throw, and a missed check makes the enumerator
 * and the CR 601.2c validator wrong *in the same way* because they are the same function by design
 * (ADR-005). So the T and B letters are pinned here, by hand, and nowhere else.
 */
class ProtectionSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // Warder prints protection from red; Paladin prints protection from monocolored. Redcap is
        // mono-red, Whitecap mono-white, Hybrid is red *and* white, and Bear is costless and so
        // colourless (CR 105.4).
        fun board() =
            keywordState(
                listOf(
                    combatObject(0, "Warder", alice),
                    combatObject(1, "Paladin", alice),
                    combatObject(2, "Bear", alice),
                    combatObject(3, "Redcap", bob),
                    combatObject(4, "Whitecap", bob),
                    combatObject(5, "Hybrid", bob),
                ),
            )

        // ---- T: CR 702.16b, can't be targeted ----

        "CR 702.16b: a red spell can't target a creature with protection from red, but a white one can" {
            val state = board()
            val warder = Target.Permanent(state.creatureOf("Warder", alice).id)
            val red = state.creatureOf("Redcap", bob).id
            val white = state.creatureOf("Whitecap", bob).id

            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(red)) shouldNotContain warder
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(white)) shouldContain warder
        }

        "CR 702.16b: protection is quality-relative, not controller-relative — your own red spell is barred too" {
            val state = board()
            val warder = Target.Permanent(state.creatureOf("Warder", alice).id)
            // alice owns Warder, and hexproof would let her target it freely. Protection does not
            // care who controls the source, only what it is.
            val ownRed = combatObject(6, "Redcap", alice)
            val withOwnRed = keywordState(state.sharedZones.battlefield + ownRed)

            legalTargets(withOwnRed, TargetSpec.AnyTarget, alice, Chooser.Spell(ownRed.id)) shouldNotContain warder
        }

        "CR 702.16a: 'monocolored' is a characteristic, not a colour — mono sources are barred, multicolored are not" {
            val state = board()
            val paladin = Target.Permanent(state.creatureOf("Paladin", alice).id)
            val red = state.creatureOf("Redcap", bob).id
            val white = state.creatureOf("Whitecap", bob).id
            val hybrid = state.creatureOf("Hybrid", bob).id
            val colorless = state.creatureOf("Bear", alice).id

            // Exactly one colour: barred, whichever colour it is.
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(red)) shouldNotContain paladin
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(white)) shouldNotContain paladin
            // Two colours, and CR 105.4's no-colour-at-all: neither is monocolored. This is the
            // printed card's famous blind spot, reproduced faithfully rather than tidied up.
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(hybrid)) shouldContain paladin
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Spell(colorless)) shouldContain paladin
        }

        "CR 702.16c + CR 601.2c: an Aura with the stated quality is not offered a protected creature to enchant" {
            val state = board()
            val enchantable = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
            val warder = Target.Permanent(state.creatureOf("Warder", alice).id)
            val red = state.creatureOf("Redcap", bob).id
            val white = state.creatureOf("Whitecap", bob).id

            // The E letter at cast time is the same filter as T, which is why it costs nothing extra.
            legalTargets(state, enchantable, bob, Chooser.Spell(red)) shouldNotContain warder
            legalTargets(state, enchantable, bob, Chooser.Spell(white)) shouldContain warder
        }

        "CR 702.16b: an *ability* is barred by its source's quality, not by the ability's own colour" {
            // The half `P-ABILSOURCE` closed. CR 702.16b's second clause — "can't be targeted by
            // abilities from a source with the stated quality" — reads the *source*, so a red
            // permanent's activated or triggered ability cannot target a creature with protection
            // from red even though the ability itself has no colour at all (CR 113.7b).
            val state = board()
            val warder = Target.Permanent(state.creatureOf("Warder", alice).id)

            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Ability(CardRef("Redcap"))) shouldNotContain warder
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Ability(CardRef("Whitecap"))) shouldContain warder
        }

        "CR 702.16a + CR 113.7b: 'protection from monocolored' reads the ability's source, not the ability" {
            val state = board()
            val paladin = Target.Permanent(state.creatureOf("Paladin", alice).id)

            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Ability(CardRef("Redcap"))) shouldNotContain paladin
            // Two colours is not monocolored (CR 105.4), whether the source is a spell or an ability.
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Ability(CardRef("Hybrid"))) shouldContain paladin
        }

        "CR 113.7c: an ability's source is last known information, so a sacrificed source still bars its target" {
            // Tinder Wall's shape: the ability's cost sacrificed its own source, so the source is no
            // longer on the battlefield and carries a *new* object id in the graveyard (CR 400.7).
            // [Chooser.Ability] captures the card rather than an id precisely so the CR 608.2b re-check
            // can still answer CR 702.16b here instead of failing to find the source.
            val state = board()
            val warder = Target.Permanent(state.creatureOf("Warder", alice).id)
            // A board on which no object named "Redcap" exists at all — the source is gone.
            val sourceless =
                keywordState(state.sharedZones.battlefield.filterNot { it.card == CardRef("Redcap") })

            sourceless.sharedZones.battlefield.none { it.card == CardRef("Redcap") } shouldBe true
            legalTargets(sourceless, TargetSpec.AnyTarget, bob, Chooser.Ability(CardRef("Redcap"))) shouldNotContain
                warder
        }

        "ADR-005: a protected object reached with no prospective source at all fails loudly rather than being offered" {
            // [Chooser.Nobody] is now the *only* caller that cannot answer CR 702.16b — a unit test
            // asking what the board offers, with no spell and no ability behind the question. Before
            // `P-ABILSOURCE` every ability site landed here by passing `Chooser.Nobody`, which is exactly
            // the defect this packet removed. Offering the object anyway would be a silently illegal
            // option, so it throws instead.
            val state = board()
            shouldThrow<IllegalStateException> {
                legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Nobody)
            }
        }

        "ADR-005: an object with no protection needs no source, so the gap costs nothing where it is not needed" {
            // The common case short-circuits before the source is consulted — which is why the gate
            // above is unreachable while nothing in the pool prints or grants protection.
            val plain =
                keywordState(
                    listOf(combatObject(0, "Bear", alice), combatObject(1, "Ogre", bob)),
                )
            legalTargets(plain, TargetSpec.AnyTarget, bob, Chooser.Nobody) shouldContain
                Target.Permanent(plain.creatureOf("Bear", alice).id)
        }

        // ---- B: CR 702.16f, can't be blocked ----

        "CR 702.16f: an attacker with protection from red can't be blocked by a red creature" {
            // Warder (protection from red) attacks into a red Redcap and a colourless Bear.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Warder")),
                    bobField = listOf(Combatant("Redcap"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Warder").pending<DecisionRequest.DeclareBlockers>()

            request.blockPairs() shouldContainExactly listOf("Bear" to "Warder")
        }

        "CR 702.16f: protection on a *blocker* neither enables nor prevents blocking — the direction matters" {
            // Redcap attacks; Warder has protection from red but blocks it happily (it merely takes
            // no damage, CR 702.16e). Getting the direction backwards would drop this pairing.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Redcap")),
                    bobField = listOf(Combatant("Warder"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Redcap").pending<DecisionRequest.DeclareBlockers>()

            request.blockPairs() shouldContainExactlyInAnyOrder
                listOf("Warder" to "Redcap", "Bear" to "Redcap")
        }

        // ---- E: CR 702.16c / CR 704.5m, the Aura falls off ----

        "CR 704.5m + CR 702.16c: an Aura with the stated quality falls off a still-present protected creature" {
            // The case the SBA's old comment called unreachable: "no type-changing effect makes a
            // still-present object illegal". Protection makes that false.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Warder", alice),
                        combatObject(1, "Red Aura", alice, attachedTo = 0),
                    ),
                )
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.AuraFallsOff(ObjectId(1)))
        }

        "CR 704.5m: an Aura without the stated quality stays attached to a protected creature" {
            // Mask of Law and Grace is itself white and grants protection from black and red, so it
            // removes neither itself nor any other white Aura. Here the green Ward Aura stays put.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Warder", alice),
                        combatObject(1, "Ward Aura", alice, attachedTo = 0),
                    ),
                )
            applicableStateBasedActions(state).shouldBeEmpty()
        }

        // ---- D: CR 702.16e, damage is prevented ----

        "CR 613.1f + CR 702.16e: an Aura-granted protection prevents damage exactly as a printed one does" {
            // Ward Aura grants protection from red to a plain Bear; a red source then deals nothing.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Bear", alice),
                        combatObject(1, "Ward Aura", alice, attachedTo = 0),
                        combatObject(2, "Redcap", bob),
                    ),
                )
            val bear = state.creatureOf("Bear", alice)
            val red = state.creatureOf("Redcap", bob)

            val after = dealDamage(state, DamageSource(red.id, red.card), Target.Permanent(bear.id), 2)
            after.sharedZones.battlefield
                .first { it.id == bear.id }
                .damageMarked shouldBe 0
        }

        "CR 702.16m: multiple instances of protection from the same quality are redundant" {
            // Warder prints protection from red and Ward Aura grants it again; the set unions them to
            // one, so nothing double-counts and the behaviour is unchanged.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Warder", alice),
                        combatObject(1, "Ward Aura", alice, attachedTo = 0),
                    ),
                )
            layeredCharacteristics(state, ObjectId(0))
                .protections shouldContainExactly listOf(Quality.OfColor(Color.RED))
        }
    })
