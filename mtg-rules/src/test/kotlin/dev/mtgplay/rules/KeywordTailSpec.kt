package dev.mtgplay.rules

import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.effect.dealDamage
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.effectiveEvasions
import dev.mtgplay.rules.engine.hasSubtype
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredPower
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The remaining keyword tail — deathtouch (CR 702.2) and changeling (CR 702.73) — and the two
 * framework halves the same cards forced: a conditional static ability (CR 604.3, `FW-CONDSTATIC`)
 * and a **granted** evasion (CR 509.1b). Fixture objects only; `mtg-rules` names no card.
 *
 * Each keyword is asserted at *every* site it changes, because the failure mode for all three is the
 * same shape: a keyword honoured at one site and missed at another is a silently wrong game rather
 * than a crash, and under ADR-005 a missed site changes which options are enumerated at all. Every
 * assertion below is paired with its control — the same board without the keyword — because a
 * lethality or block-legality number means nothing on its own.
 */
class KeywordTailSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // --- Deathtouch, CR 510.1c / CR 702.19b: what counts as lethal ---

        "CR 702.2b: a deathtouching trampler needs only 1 per blocker, so its CR 702.19e excess is larger" {
            // Venomtrampler is a 4/4 with deathtouch and trample, blocked by a Bear 2/2. Lethal to the
            // Bear is 1, not 2 (CR 702.2b), so the excess is 3 — and the excess *is* the enumerated
            // option list (ADR-005), which is what makes a missed site an illegal or missing option.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Venomtrampler")),
                    bobField = listOf(Combatant("Bear")),
                )
            val afterBlocks =
                engine.declareBlocks(
                    engine.toDeclareBlockers(state, "Venomtrampler"),
                    "Bear" to "Venomtrampler",
                )
            val request =
                engine.passPriorityRound(afterBlocks).pending<DecisionRequest.AssignTrampleDamage>()

            request.options shouldBe listOf(0, 1, 2, 3)
        }

        "CR 702.19e: the same 4/4 trampler *without* deathtouch has an excess of only 2 over the same Bear" {
            // The control that makes the case above a deathtouch result rather than an arithmetic
            // coincidence: Charger is also a 4/4 trampler, and its lethal to the Bear is the Bear's
            // toughness (2), leaving 2.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Charger")),
                    bobField = listOf(Combatant("Bear")),
                )
            val afterBlocks =
                engine.declareBlocks(engine.toDeclareBlockers(state, "Charger"), "Bear" to "Charger")
            val request =
                engine.passPriorityRound(afterBlocks).pending<DecisionRequest.AssignTrampleDamage>()

            request.options shouldBe listOf(0, 1, 2)
        }

        // --- Deathtouch, CR 704.5h: destruction ---

        "CR 704.5h: any nonzero damage from a deathtouch source destroys a creature, whatever its toughness" {
            // Venomous is a 2/2 deathtoucher; Bulwark is a 0/5 Wall. Two damage is three short of
            // lethal, so CR 704.5g cannot destroy it and only CR 704.5h can.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Venomous")),
                    bobField = listOf(Combatant("Bulwark")),
                )
            val afterBlocks =
                engine.declareBlocks(engine.toDeclareBlockers(state, "Venomous"), "Bulwark" to "Venomous")
            val after = engine.passPriorityRound(afterBlocks).pausedState

            after.sharedZones.battlefield.none { it.card == CardRef("Bulwark") } shouldBe true
        }

        "CR 704.5g: the same 0/5 Wall survives the same 2 damage from a source without deathtouch" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Bear")),
                    bobField = listOf(Combatant("Bulwark")),
                )
            val afterBlocks =
                engine.declareBlocks(engine.toDeclareBlockers(state, "Bear"), "Bulwark" to "Bear")
            val after = engine.passPriorityRound(afterBlocks).pausedState

            after.creature("Bulwark").damageMarked shouldBe 2
        }

        "CR 702.12b: an indestructible creature dealt deathtouch damage carries the record and is not destroyed" {
            // Ironhide 2/2 blocks Venomous 2/2: it takes lethal damage *and* the deathtouch record, and
            // CR 702.12b exempts it from both destruction actions while CR 704.5f does not apply.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Venomous")),
                    bobField = listOf(Combatant("Ironhide")),
                )
            val afterBlocks =
                engine.declareBlocks(engine.toDeclareBlockers(state, "Venomous"), "Ironhide" to "Venomous")
            val after = engine.passPriorityRound(afterBlocks).pausedState

            after.creature("Ironhide").dealtDeathtouchDamage shouldBe true
        }

        "CR 702.2: a *granted* deathtouch is honoured, because the source keyword is read through the layer seam" {
            // Venom Aura grants deathtouch in CR 613.1f layer 6 — the same shape Toxin Analysis's
            // until-end-of-turn grant has, and the only way an Aura-granted keyword reaches a damage
            // event at all.
            val bear = combatObject(0, "Bear", alice)
            val wall = combatObject(1, "Bulwark", bob)
            val state = keywordState(listOf(bear, wall, combatObject(2, "Venom Aura", alice, attachedTo = 0)))

            val damaged = dealDamage(state, DamageSource(bear.id, bear.card), Target.Permanent(wall.id), 1)

            damaged.creature("Bulwark").dealtDeathtouchDamage shouldBe true
        }

        "CR 702.2b: a source without deathtouch marks the same damage and sets no record" {
            val bear = combatObject(0, "Bear", alice)
            val wall = combatObject(1, "Bulwark", bob)
            val state = keywordState(listOf(bear, wall))

            val damaged = dealDamage(state, DamageSource(bear.id, bear.card), Target.Permanent(wall.id), 1)

            damaged.creature("Bulwark").damageMarked shouldBe 1
            damaged.creature("Bulwark").dealtDeathtouchDamage shouldBe false
        }

        "CR 514.2: cleanup wipes the deathtouch record in the same transition as the damage it describes" {
            val bear = combatObject(0, "Bear", alice)
            val wall = combatObject(1, "Bulwark", bob)
            val state = keywordState(listOf(bear, wall, combatObject(2, "Venom Aura", alice, attachedTo = 0)))
            val damaged = dealDamage(state, DamageSource(bear.id, bear.card), Target.Permanent(wall.id), 1)

            val cleaned = cleanupRemoveDamageAndEndEffects(damaged)

            cleaned.creature("Bulwark").damageMarked shouldBe 0
            cleaned.creature("Bulwark").dealtDeathtouchDamage shouldBe false
        }

        // --- The haste evasion, CR 509.1b ---

        "CR 509.1b: a blockable-only-by-haste attacker is blockable by a haste creature, not by a ground Bear" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ghost")),
                    bobField = listOf(Combatant("Hasty"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Ghost").pending<DecisionRequest.DeclareBlockers>()

            request.blockPairs() shouldContainExactly listOf("Hasty" to "Ghost")
        }

        "CR 509.1b: the same two blockers may both block an attacker without the evasion" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ogre")),
                    bobField = listOf(Combatant("Hasty"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Ogre").pending<DecisionRequest.DeclareBlockers>()

            request.blockPairs() shouldContainExactlyInAnyOrder listOf("Hasty" to "Ogre", "Bear" to "Ogre")
        }

        "CR 613.1f: an until-end-of-turn evasion grant lands in layer 6 and narrows the enumerated blocks" {
            // Gingerbrute's shape exactly: the attacker prints no evasion and grants itself one for the
            // turn. Before the grant both blockers are offered; after it, only the one with haste is.
            val ogre = combatObject(0, "Ogre", alice)
            val state =
                keywordState(
                    listOf(ogre, combatObject(1, "Hasty", bob), combatObject(2, "Bear", bob)),
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
                )

            effectiveEvasions(state, ogre.id).shouldBeEmpty()

            val granted =
                applyUntilEndOfTurn(
                    state = state,
                    affected = ogre.id,
                    modification =
                        ContinuousModification(
                            grantedEvasions = persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_HASTE),
                        ),
                    sourceCard = CardRef("Ogre"),
                    source = ogre.id,
                )

            effectiveEvasions(granted, ogre.id) shouldContainExactly listOf(Evasion.BLOCKABLE_ONLY_BY_HASTE)
            engine
                .toDeclareBlockers(granted, "Ogre")
                .pending<DecisionRequest.DeclareBlockers>()
                .blockPairs() shouldContainExactly listOf("Hasty" to "Ogre")
        }

        "CR 514.2: a granted evasion ends at cleanup, so the same board is fully blockable next turn" {
            val ogre = combatObject(0, "Ogre", alice)
            val state = keywordState(listOf(ogre))
            val granted =
                applyUntilEndOfTurn(
                    state = state,
                    affected = ogre.id,
                    modification =
                        ContinuousModification(
                            grantedEvasions = persistentSetOf(Evasion.BLOCKABLE_ONLY_BY_HASTE),
                        ),
                    sourceCard = CardRef("Ogre"),
                    source = ogre.id,
                )

            effectiveEvasions(cleanupRemoveDamageAndEndEffects(granted), ogre.id).shouldBeEmpty()
        }

        // --- FW-CONDSTATIC, CR 604.3 ---

        "CR 604.3: a conditional static ability applies only while its condition holds" {
            // Raider is a 1/2 that gets +1/+0 and haste "as long as you control an artifact".
            val raider = combatObject(0, "Raider", alice, summoningSick = true)
            val bare = keywordState(listOf(raider))

            layeredPower(bare, raider.id) shouldBe 1
            (Keyword.HASTE in layeredCharacteristics(bare, raider.id).keywords) shouldBe false

            val withArtifact = keywordState(listOf(raider, combatObject(1, "Trinket", alice)))

            layeredPower(withArtifact, raider.id) shouldBe 2
            (Keyword.HASTE in layeredCharacteristics(withArtifact, raider.id).keywords) shouldBe true
        }

        "CR 604.3: the condition reads *your* permanents, so an opponent's artifact does not switch it on" {
            val raider = combatObject(0, "Raider", alice, summoningSick = true)
            val state = keywordState(listOf(raider, combatObject(1, "Trinket", bob)))

            layeredPower(state, raider.id) shouldBe 1
            (Keyword.HASTE in layeredCharacteristics(state, raider.id).keywords) shouldBe false
        }

        "CR 604.3: the effect stops applying the moment the condition fails, with nothing on the stack" {
            // The property a triggered-ability encoding of the same text would get wrong: removing the
            // artifact takes the bonus away immediately, not at some later checkpoint.
            val raider = combatObject(0, "Raider", alice, summoningSick = true)
            val withArtifact = keywordState(listOf(raider, combatObject(1, "Trinket", alice)))
            layeredPower(withArtifact, raider.id) shouldBe 2

            val removed =
                withArtifact.copy(
                    sharedZones =
                        withArtifact.sharedZones.copy(
                            battlefield =
                                withArtifact.sharedZones.battlefield
                                    .filterNot { it.card == CardRef("Trinket") }
                                    .toPersistentList(),
                        ),
                )

            layeredPower(removed, raider.id) shouldBe 1
            removed.sharedZones.stack.shouldBeEmpty()
        }

        "CR 702.10b: the conditional haste is honoured at attacker enumeration, not merely computed" {
            // ADR-005: the artifact's presence is the difference between the creature being *offered*
            // as an attacker on the turn it arrives and there being no attack decision at all.
            val raider = combatObject(0, "Raider", alice, summoningSick = true)
            val turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS)
            val withArtifact = keywordState(listOf(raider, combatObject(1, "Trinket", alice)), turn = turn)

            pausedRequestOf<DecisionRequest.DeclareAttackers>(withArtifact)
                .attackerNames() shouldContainExactly listOf("Raider")
        }

        // --- Changeling, CR 702.73 ---

        "CR 702.73a: a changeling has every *creature* type" {
            // Mimic prints no subtype at all, so every subtype it matches comes from CR 702.73a.
            val mimic = combatObject(0, "Mimic", alice)
            val state = keywordState(listOf(mimic))

            hasSubtype(state, mimic.id, Subtype("Elf")) shouldBe true
            hasSubtype(state, mimic.id, Subtype("Goblin")) shouldBe true
            hasSubtype(state, mimic.id, Subtype("Dragon")) shouldBe true
        }

        "CR 205.3m: a changeling has no land, artifact or enchantment type — not a Forest, Mountain or Food" {
            // The half that is easy to lose, and the half whose loss would silently break Gingerbread
            // Cabin's Forest count and Fireblast's Mountain sacrifice.
            val mimic = combatObject(0, "Mimic", alice)
            val state = keywordState(listOf(mimic))

            hasSubtype(state, mimic.id, Subtype("Forest")) shouldBe false
            hasSubtype(state, mimic.id, Subtype("Mountain")) shouldBe false
            hasSubtype(state, mimic.id, Subtype("Food")) shouldBe false
            hasSubtype(state, mimic.id, Subtype("Aura")) shouldBe false
        }

        "CR 205.3: a creature without changeling has only its printed subtypes" {
            val bear = combatObject(0, "Bear", alice)
            val state = keywordState(listOf(bear))

            hasSubtype(state, bear.id, Subtype("Elf")) shouldBe false
        }

        "CR 205.3: an unclassified subtype word fails loudly rather than guessing a category" {
            val mimic = combatObject(0, "Mimic", alice)
            val state = keywordState(listOf(mimic))

            val thrown = runCatching { hasSubtype(state, mimic.id, Subtype("Sliver")) }.exceptionOrNull()

            thrown shouldNotBe null
            thrown?.message?.contains("CR 205.3") shouldBe true
        }
    })
