package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.PreventionEffect
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.effect.damageCannotBePreventedThisTurn
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.effect.preventDamageFromColorThisTurn
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The **global prevention store** (`FW-PREVENT2`, CR 615): the two effects that live in it and the one
 * application point that reads them.
 *
 * Everything here is pinned by hand, for `ProtectionSpec`'s reason and one more. `EnumerationProbe`
 * structurally cannot catch a prevention bug: prevention changes an *outcome*, never an option set
 * (docs/design/protection.md §6), so a shield that failed to apply would leave every enumerated action
 * exactly where it was and simply produce the wrong life total. And a shield that outlived its turn
 * leaves no other trace in the state at all — only the CR 514.2 wear-off and the acceptance invariant
 * that mirrors it can see it.
 *
 * The three properties that matter are the three CR 615 corners: a shield keys on the **source's**
 * colour and not on any recipient; CR 615.9's disabler beats **every** prevention effect including
 * protection's, whenever either was created; and both end at CR 514.2.
 */
class PreventionStoreSpec :
    StringSpec({
        // "Redcap" is mono-red, "Whitecap" mono-white, "Bear" costless and so colourless (CR 105.4);
        // "Warder" prints protection from red. All four come from `combatDefinitions`.
        val redSource = DamageSource(objectId = null, card = CardRef("Redcap"))
        val whiteSource = DamageSource(objectId = null, card = CardRef("Whitecap"))
        val colourlessSource = DamageSource(objectId = null, card = CardRef("Bear"))
        val strands = CardRef("Prismatic Strands")
        val flaringPain = CardRef("Flaring Pain")

        fun board() =
            keywordState(
                listOf(
                    combatObject(0, "Warder", alice),
                    combatObject(1, "Bear", alice),
                ),
            )

        "CR 615.1: a colour shield prevents damage from sources of that colour, to a player" {
            val shielded = preventDamageFromColorThisTurn(board(), Color.RED, strands)
            val hit = dealDamage(shielded, redSource, Target.Player(bob), 3)

            hit.players.getValue(bob).life shouldBe shielded.players.getValue(bob).life
            hit.events shouldContain GameEvent.DamagePrevented(redSource, Target.Player(bob), 3)
            // CR 615.6: prevented damage never happens, so nothing narrates it as dealt — which is what
            // keeps lifelink and every damage-dealt trigger from firing.
            hit.events shouldNotContain GameEvent.DamageDealt(redSource, Target.Player(bob), 3)
        }

        "CR 615.1: the shield keys on the source's colour, so a white source is unaffected by a red shield" {
            val shielded = preventDamageFromColorThisTurn(board(), Color.RED, strands)
            val hit = dealDamage(shielded, whiteSource, Target.Player(bob), 3)

            hit.players.getValue(bob).life shouldBe shielded.players.getValue(bob).life - 3
        }

        "CR 105.4: a colourless source is caught by no shield, because colourless is not a colour" {
            val shielded = preventDamageFromColorThisTurn(board(), Color.RED, strands)
            // Every colour in turn: none of the five can name the absence of colour.
            Color.entries.forEach { colour ->
                val onColour = preventDamageFromColorThisTurn(shielded, colour, strands)
                val hit = dealDamage(onColour, colourlessSource, Target.Player(bob), 2)
                hit.players.getValue(bob).life shouldBe onColour.players.getValue(bob).life - 2
            }
        }

        "CR 615.1: the shield covers permanents as well as players, whoever controls them" {
            val shielded = preventDamageFromColorThisTurn(board(), Color.RED, strands)
            val bear = Target.Permanent(shielded.creatureOf("Bear", alice).id)
            val hit = dealDamage(shielded, redSource, bear, 2)

            val marked = hit.sharedZones.battlefield.single { it.id == bear.id }
            marked.damageMarked shouldBe 0
        }

        "CR 615.9: Flaring Pain turns off a colour shield, whichever was created first" {
            val shieldFirst =
                damageCannotBePreventedThisTurn(
                    preventDamageFromColorThisTurn(board(), Color.RED, strands),
                    flaringPain,
                )
            // The order the CR does *not* care about: a shield created in response to the disabler is
            // equally inert, which is the line Flaring Pain is actually played to beat.
            val disablerFirst =
                preventDamageFromColorThisTurn(
                    damageCannotBePreventedThisTurn(board(), flaringPain),
                    Color.RED,
                    strands,
                )

            listOf(shieldFirst, disablerFirst).forEach { state ->
                val hit = dealDamage(state, redSource, Target.Player(bob), 3)
                hit.players.getValue(bob).life shouldBe state.players.getValue(bob).life - 3
            }
            // It removes nothing: CR 615.9 says an inapplicable prevention effect "doesn't do anything",
            // not that it ceases to exist.
            shieldFirst.preventionEffects.size shouldBe 2
        }

        "CR 615.9: Flaring Pain also turns off protection's own damage prevention (CR 702.16e)" {
            val board = board()
            val warder = Target.Permanent(board.creatureOf("Warder", alice).id)
            // Without it, CR 702.16e prevents the red source's damage to a creature with pro-red.
            dealDamage(board, redSource, warder, 2)
                .sharedZones.battlefield
                .single { it.id == warder.id }
                .damageMarked shouldBe 0

            val disabled = damageCannotBePreventedThisTurn(board, flaringPain)
            dealDamage(disabled, redSource, warder, 2)
                .sharedZones.battlefield
                .single { it.id == warder.id }
                .damageMarked shouldBe 2
        }

        "CR 611.2: both effects are stored as until-end-of-turn, stamped with the turn that made them" {
            val state =
                damageCannotBePreventedThisTurn(
                    preventDamageFromColorThisTurn(board(), Color.WHITE, strands),
                    flaringPain,
                )

            state.preventionEffects.map { it.effect } shouldBe
                listOf(PreventionEffect.PreventDamageFromColor(Color.WHITE), PreventionEffect.DamageCantBePrevented)
            state.preventionEffects.forEach {
                it.duration shouldBe EffectDuration.UntilEndOfTurn
                it.createdOnTurn shouldBe TURN_NUMBER
            }
            state.events shouldContain
                GameEvent.PreventionEffectCreated(strands, PreventionEffect.PreventDamageFromColor(Color.WHITE))
        }

        "CR 514.2: the cleanup turn-based action ends every stored prevention effect" {
            val state =
                damageCannotBePreventedThisTurn(
                    preventDamageFromColorThisTurn(board(), Color.RED, strands),
                    flaringPain,
                )

            cleanupRemoveDamageAndEndEffects(state).preventionEffects.shouldBeEmpty()
        }
    })
