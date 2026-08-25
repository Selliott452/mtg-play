package dev.mtgplay.rules

import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.legalTargets
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/**
 * Hexproof as a targeting restriction on enumeration (CR 702.11): a hexproof object can't be the
 * target of spells or abilities an *opponent* controls, but its own controller targets it freely.
 * The property is enforced in one place — [legalTargets] — so it holds for "any target" spells
 * (Lightning Bolt's [TargetSpec.AnyTarget]) and Auras ([TargetSpec.Enchantable]) alike, and for
 * printed hexproof and aura-granted hexproof (CR 613 layer 6) the same way. Control is ownership in
 * the MVP pool.
 */
class HexproofTargetingSpec :
    StringSpec({
        // alice and bob each control one hexproof Warden and one plain body; distinct owners let the
        // exclusion be checked from both directions.
        fun board() =
            keywordState(
                listOf(
                    combatObject(0, "Warden", alice),
                    combatObject(1, "Ogre", alice),
                    combatObject(2, "Warden", bob),
                    combatObject(3, "Bear", bob),
                ),
            )

        "CR 702.11: an any-target spell can't target an opponent's hexproof creature but can target its own" {
            val state = board()
            val aliceWarden = Target.Permanent(state.creatureOf("Warden", alice).id)
            val bobWarden = Target.Permanent(state.creatureOf("Warden", bob).id)

            val aliceTargets = legalTargets(state, TargetSpec.AnyTarget, alice, Chooser.Nobody)
            // alice may target her own hexproof Warden, but not bob's.
            aliceTargets shouldContain aliceWarden
            aliceTargets shouldNotContain bobWarden
            // Plain bodies are always targetable, and both players remain legal targets (CR 115.4).
            aliceTargets shouldContain Target.Permanent(state.creatureOf("Bear", bob).id)
            aliceTargets shouldContain Target.Player(alice)
            aliceTargets shouldContain Target.Player(bob)

            // The exclusion is symmetric: bob may target his own Warden, not alice's.
            val bobTargets = legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Nobody)
            bobTargets shouldContain bobWarden
            bobTargets shouldNotContain aliceWarden
        }

        "CR 702.11 and 601.2c: an Aura can enchant its own hexproof creature but not an opponent's" {
            val state = board()
            val enchantable = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
            val aliceTargets = legalTargets(state, enchantable, alice, Chooser.Nobody)

            // The Bogles strategy: alice enchants her own hexproof Warden…
            aliceTargets shouldContain Target.Permanent(state.creatureOf("Warden", alice).id)
            // …but her Aura cannot enchant bob's hexproof Warden.
            aliceTargets shouldNotContain Target.Permanent(state.creatureOf("Warden", bob).id)
            // A non-hexproof opponent creature is still enchantable (Rancor on their Grizzly Bears).
            aliceTargets shouldContain Target.Permanent(state.creatureOf("Bear", bob).id)
        }

        "CR 613 layer 6: aura-granted hexproof excludes opponent targeting exactly as printed hexproof does" {
            // bob's plain Ogre gains hexproof from a Hex Aura attached to it (a layer-6 keyword grant).
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Ogre", bob),
                        combatObject(1, "Hex Aura", bob, attachedTo = 0),
                    ),
                )
            val grantedHexproof = Target.Permanent(state.creatureOf("Ogre", bob).id)

            // alice (bob's opponent) can no longer target the now-hexproof Ogre…
            legalTargets(state, TargetSpec.AnyTarget, alice, Chooser.Nobody) shouldNotContain grantedHexproof
            // …but bob still can (his own object).
            legalTargets(state, TargetSpec.AnyTarget, bob, Chooser.Nobody) shouldContain grantedHexproof
        }

        "enumeration completeness: removing the hexproof grant makes the creature targetable by the opponent again" {
            // Same board without the aura: the printed-plain Ogre is targetable by alice.
            val state = keywordState(listOf(combatObject(0, "Ogre", bob)))
            legalTargets(state, TargetSpec.AnyTarget, alice, Chooser.Nobody) shouldContain
                Target.Permanent(state.creatureOf("Ogre", bob).id)
        }
    })
