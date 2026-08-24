package dev.mtgplay.rules

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain

/**
 * "Target creature" as its own target spec (CR 115.1a): the enumeration offers exactly the creatures
 * on the battlefield and never a player, which is what separates it from "any target" (CR 115.4).
 * `mtg-rules` names no card, so the test exercises the spec directly.
 */
class TargetCreatureSpec :
    StringSpec({
        fun board() =
            keywordState(
                listOf(
                    combatObject(0, "Ogre", alice),
                    combatObject(1, "Bear", bob),
                ),
            )

        "CR 115.1a: target creature enumerates exactly the creatures, in battlefield order" {
            val state = board()
            legalTargets(state, TargetSpec.TargetCreature, alice) shouldContainExactly
                listOf(
                    Target.Permanent(state.creatureOf("Ogre", alice).id),
                    Target.Permanent(state.creatureOf("Bear", bob).id),
                )
        }

        "CR 115.1a: no player is a legal choice for target creature, though any-target offers both" {
            val state = board()
            val creatureTargets = legalTargets(state, TargetSpec.TargetCreature, alice)
            creatureTargets shouldNotContain Target.Player(alice)
            creatureTargets shouldNotContain Target.Player(bob)
            // The same board under CR 115.4 does offer them — the two specs are genuinely different.
            legalTargets(state, TargetSpec.AnyTarget, alice) shouldContain Target.Player(bob)
        }

        "CR 302.1: a non-creature permanent is not a legal choice for target creature" {
            // A Hex Aura is a permanent on the battlefield but not a creature.
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Ogre", alice),
                        combatObject(1, "Hex Aura", alice, attachedTo = 0),
                    ),
                )
            legalTargets(state, TargetSpec.TargetCreature, alice) shouldContainExactly
                listOf(Target.Permanent(state.creatureOf("Ogre", alice).id))
        }

        "CR 702.11: target creature can't choose an opponent's hexproof creature but can choose its own" {
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Warden", alice),
                        combatObject(1, "Warden", bob),
                    ),
                )
            val aliceWarden = Target.Permanent(state.creatureOf("Warden", alice).id)
            val bobWarden = Target.Permanent(state.creatureOf("Warden", bob).id)

            legalTargets(state, TargetSpec.TargetCreature, alice) shouldContainExactly listOf(aliceWarden)
            legalTargets(state, TargetSpec.TargetCreature, bob) shouldContainExactly listOf(bobWarden)
        }

        "CR 115.1a: with no creature on the battlefield target creature enumerates nothing at all" {
            // The reachable uncastable case a players-only spec never has (CR 601.2c).
            legalTargets(keywordState(emptyList()), TargetSpec.TargetCreature, alice).shouldBeEmpty()
        }
    })
