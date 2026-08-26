package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.TapRequirement
import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.rules.engine.optionalCostPayableWith
import dev.mtgplay.rules.engine.tapSatisfiable
import dev.mtgplay.rules.engine.tappableFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The two **non-mana cast-cost option sets** wave 8 added (CR 601.2h): the tap cost a flashback may
 * carry (`FW-PREVENT2`, Prismatic Strands) and the optional additional cost with a chosen object
 * (`FW-BARGAIN`, Troublemaker Ouphe).
 *
 * Both are pinned here rather than left to `EnumerationProbe`, and for opposite reasons. An
 * **over**-enumeration the probe does catch — it replays each candidate through `advance` and watches
 * it throw. An **under**-enumeration it structurally cannot: an option that is never offered is never
 * probed, and the two failures this file guards against are both of that kind. Excluding a
 * summoning-sick creature from a tap cost, or forgetting that a token pays a bargain, deletes a legal
 * line silently (ADR-005).
 */
class NonManaCastCostSpec :
    StringSpec({
        val whiteCreature = TapRequirement(count = 1, color = Color.WHITE, cardType = CardType.CREATURE)

        // A 1/1 colourless token, registered like any other definition — "this object is a token" is
        // `definitions[card] is TokenDefinition`, so the state's registry is where tokenhood lives.
        val tokenRef = CardRef.token("Spawn Token")
        val tokenDefinition =
            TokenDefinition(
                characteristics =
                    PrintedCharacteristics(
                        // CR 111.1: the *name characteristic*; the ref carries the token marker.
                        name = tokenRef.printedName,
                        manaCost = null,
                        supertypes = persistentSetOf(),
                        cardTypes = persistentSetOf(CardType.CREATURE),
                        subtypes = persistentSetOf(),
                        powerToughness = PrintedPowerToughness(power = 1, toughness = 1),
                    ),
            )

        // ---- the flashback tap cost (CR 702.34c) ----

        "CR 601.2h: only untapped white creatures the caster controls pay a white-creature tap cost" {
            val state =
                keywordState(
                    listOf(
                        combatObject(0, "Whitecap", alice),
                        // Tapped: a tap cost can only ever be paid by an untapped permanent.
                        combatObject(1, "Whitecap", alice).copy(tapped = true),
                        // Wrong colour, wrong controller, and colourless respectively.
                        combatObject(2, "Redcap", alice),
                        combatObject(3, "Whitecap", bob),
                        combatObject(4, "Bear", alice),
                    ),
                )

            tappableFor(state, alice, whiteCreature).map { it.id.value } shouldBe listOf(0L)
            tapSatisfiable(state, alice, whiteCreature) shouldBe true
            tapSatisfiable(state, alice, whiteCreature.copy(count = 2)) shouldBe false
            // No tap cost at all is trivially satisfiable, whatever the board looks like.
            tapSatisfiable(state, alice, null) shouldBe true
        }

        "CR 302.6: a summoning-sick creature pays a spell's tap cost — the restriction is about abilities" {
            // CR 302.6 restricts the {T} symbol in an activated ability *of that permanent*. Prismatic
            // Strands' flashback is a cost of a **spell**, and the creature is the source of nothing —
            // so a creature that arrived this turn is a legal answer, and excluding it would delete a
            // real and frequently-correct line.
            val state = keywordState(listOf(combatObject(0, "Whitecap", alice, summoningSick = true)))

            tappableFor(state, alice, whiteCreature).map { it.id.value } shouldBe listOf(0L)
        }

        "CR 601.2h: an empty option set is what makes the cast unenumerable, not an unpayable offer" {
            val state = keywordState(listOf(combatObject(0, "Redcap", alice)))

            tappableFor(state, alice, whiteCreature).shouldBeEmpty()
            tapSatisfiable(state, alice, whiteCreature) shouldBe false
        }

        // ---- bargain's optional additional cost (CR 702.166a) ----

        "CR 702.166a: bargain unions artifacts, enchantments and tokens the caster controls" {
            val base =
                keywordState(
                    listOf(
                        // An Aura is an enchantment (CR 303), so it pays.
                        combatObject(0, "Ward Aura", alice),
                        // An ordinary creature is none of the three.
                        combatObject(1, "Bear", alice),
                        // A token pays by being a token, whatever its card types say.
                        combatObject(2, tokenRef.name, alice),
                        // An opponent's enchantment does not: CR 601.2h pays with your own permanents.
                        combatObject(3, "Ward Aura", bob),
                    ),
                )
            val state = base.copy(definitions = (base.definitions + (tokenRef to tokenDefinition)).toPersistentMap())

            optionalCostPayableWith(state, alice, OptionalAdditionalCost.Bargain).map { it.id.value } shouldBe
                listOf(0L, 2L)
        }

        "CR 702.166a: a board with none of the three offers nothing, so the announcement is never surfaced" {
            val state = keywordState(listOf(combatObject(0, "Bear", alice), combatObject(1, "Ogre", alice)))

            optionalCostPayableWith(state, alice, OptionalAdditionalCost.Bargain).shouldBeEmpty()
        }
    })
