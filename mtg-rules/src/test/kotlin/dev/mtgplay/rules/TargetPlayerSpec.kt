package dev.mtgplay.rules

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain

/**
 * "Target player" as its own target spec (CR 115.1a): the enumeration offers exactly the players and
 * never an object, which is what separates it from "any target" (CR 115.4). Thought Scour's
 * "target player mills two cards" is the clause that needs it; `mtg-rules` names no card, so the test
 * exercises the spec directly against a board that has creatures on it.
 */
class TargetPlayerSpec :
    StringSpec({
        fun board() =
            keywordState(
                listOf(
                    combatObject(0, "Ogre", alice),
                    combatObject(1, "Bear", bob),
                ),
            )

        "CR 115.1a: target player enumerates exactly the players, in turn order" {
            legalTargets(board(), TargetSpec.TargetPlayer(), alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Player(alice), Target.Player(bob))
        }

        "CR 115.1a: no creature is a legal choice for target player, though any-target offers them" {
            val state = board()
            val ogre = Target.Permanent(state.creatureOf("Ogre", alice).id)
            val bear = Target.Permanent(state.creatureOf("Bear", bob).id)
            val playerTargets = legalTargets(state, TargetSpec.TargetPlayer(), alice, Chooser.Nobody)
            playerTargets shouldNotContain ogre
            playerTargets shouldNotContain bear
            // The same board under CR 115.4 does offer them — the two specs are genuinely different.
            legalTargets(state, TargetSpec.AnyTarget, alice, Chooser.Nobody) shouldContainExactly
                listOf(Target.Player(alice), Target.Player(bob), ogre, bear)
        }

        "CR 115.1a: a player may target themself — both seats see the same two choices" {
            val state = board()
            legalTargets(state, TargetSpec.TargetPlayer(), bob, Chooser.Nobody) shouldContainExactly
                legalTargets(state, TargetSpec.TargetPlayer(), alice, Chooser.Nobody)
        }
    })
