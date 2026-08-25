package dev.mtgplay.acceptance.invariant

import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.acceptance.mountains
import dev.mtgplay.acceptance.playerWithZones
import dev.mtgplay.acceptance.twoPlayerState
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingResolutionDiscard
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PendingTriggerTargets
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.core.zone.ZoneId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The invariant checker suite: each invariant gets a handcrafted violating input that yields
 * exactly that violation, plus clean-state coverage. The two invariants that mtg-core enforces at
 * construction (zone conservation, id sanity) are violated through the checker's extracted-data
 * entry points, since a corrupt `GameState` cannot be built through the public constructor.
 */
class InvariantCheckerSpec :
    StringSpec({
        val precombatMain = Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null)

        // --- ZONE_CONSERVATION -------------------------------------------------------------

        "CR 400.7: an object id occupying two zones is exactly one ZONE_CONSERVATION violation" {
            val duplicated = GameObject(ObjectId(1), CardRef("Mountain"), alice)
            val residences =
                listOf(
                    ZoneResidence(ZoneId.Hand(alice), duplicated),
                    ZoneResidence(ZoneId.Battlefield, duplicated),
                )
            val violations = InvariantChecker.checkZoneConservation(residences)
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ZONE_CONSERVATION)
        }

        "zone conservation: distinct ids across zones produce no violation" {
            val residences =
                listOf(
                    ZoneResidence(ZoneId.Hand(alice), GameObject(ObjectId(1), CardRef("Mountain"), alice)),
                    ZoneResidence(ZoneId.Battlefield, GameObject(ObjectId(2), CardRef("Mountain"), alice)),
                )
            InvariantChecker.checkZoneConservation(residences).shouldBeEmpty()
        }

        // --- ID_SANITY ---------------------------------------------------------------------

        "CR 400.7: an object id at or above the allocation counter is exactly one ID_SANITY violation" {
            val residences =
                listOf(ZoneResidence(ZoneId.Library(alice), GameObject(ObjectId(5), CardRef("Mountain"), alice)))
            val violations = InvariantChecker.checkIdSanity(residences, nextObjectId = 3, decisionCounts = emptyList())
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ID_SANITY)
        }

        "id sanity: a negative answered-decision count is exactly one ID_SANITY violation" {
            val violations =
                InvariantChecker.checkIdSanity(
                    residences = emptyList(),
                    nextObjectId = 10,
                    decisionCounts = listOf(SeatDecisionCount(seat = 0, count = -1)),
                )
            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ID_SANITY)
        }

        "id sanity: ids below the counter and non-negative counts produce no violation" {
            val residences =
                listOf(ZoneResidence(ZoneId.Library(alice), GameObject(ObjectId(2), CardRef("Mountain"), alice)))
            InvariantChecker
                .checkIdSanity(residences, nextObjectId = 10, decisionCounts = listOf(SeatDecisionCount(0, 4)))
                .shouldBeEmpty()
        }

        // --- PRIORITY ----------------------------------------------------------------------

        "CR 117.1a: two seats holding priority is exactly one PRIORITY violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    bobState =
                        playerWithZones(library = mountains(10L..12L, bob))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly listOf(Invariant.PRIORITY)
        }

        "priority: one holder and one passer produce no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    bobState =
                        playerWithZones(library = mountains(10L..12L, bob))
                            .copy(priorityStatus = PriorityStatus.HAS_PASSED),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- DRAW_FAILURE_HONESTY ----------------------------------------------------------

        "CR 704.5c: a set empty-draw flag over a non-empty library is exactly one DRAW_FAILURE_HONESTY violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(attemptedDrawFromEmptyLibrary = true),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.DRAW_FAILURE_HONESTY)
        }

        "draw-failure honesty: a set flag over an empty library is honest and produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState =
                        playerWithZones()
                            .copy(attemptedDrawFromEmptyLibrary = true),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- CARD_CONSERVATION -------------------------------------------------------------

        "card conservation: a state missing a card against the baseline is exactly one CARD_CONSERVATION violation" {
            val baselineState =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..11L, bob)),
                    nextObjectId = 100,
                )
            val baseline = CardCensus.of(baselineState)
            val shrunk =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..10L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(shrunk, baseline).map { it.invariant } shouldContainExactly
                listOf(Invariant.CARD_CONSERVATION)
        }

        "card conservation: an unchanged multiset against the baseline produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(library = mountains(0L..1L, alice)),
                    bobState = playerWithZones(library = mountains(10L..11L, bob)),
                    nextObjectId = 100,
                )
            val baseline = CardCensus.of(state)
            InvariantChecker.check(state, baseline).shouldBeEmpty()
        }

        // --- MANA_POOL_EMPTY_AT_PAUSE --------------------------------------------------------

        "CR 500.4: a nonempty mana pool at an observed pause is exactly one MANA_POOL_EMPTY_AT_PAUSE violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(manaPool = persistentListOf(ManaType.RED)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.MANA_POOL_EMPTY_AT_PAUSE)
        }

        // The triggered-mana exemption follows the enchanted permanent, not the Aura. Utopia Sprawl
        // enchants *any* Forest, an opponent's included, and CR 605.1b gives the extra mana to whoever
        // taps the enchanted land. Keying the exemption on the Aura's controller excused the wrong seat
        // and reported the right one — invisible against Mono-Red Madness, which plays no Forests, but
        // 7,920 spurious violations over 2,000 GW Bogles mirror games.
        fun sprawlAcrossTheTable(
            aliceMana: List<ManaType>,
            bobMana: List<ManaType>,
        ): GameState {
            val bobsForest = GameObject(ObjectId(50), CardRef("Forest"), bob)
            val alicesSprawl =
                GameObject(
                    ObjectId(51),
                    CardRef("Utopia Sprawl"),
                    alice,
                    attachedTo = bobsForest.id,
                    chosenColor = Color.GREEN,
                )
            return twoPlayerState(
                turn = precombatMain,
                aliceState =
                    playerWithZones(library = mountains(0L..2L, alice))
                        .copy(manaPool = aliceMana.toPersistentList()),
                bobState =
                    playerWithZones(library = mountains(10L..12L, bob))
                        .copy(manaPool = bobMana.toPersistentList()),
                nextObjectId = 100,
            ).copy(
                sharedZones =
                    SharedZones(
                        battlefield = persistentListOf(bobsForest, alicesSprawl),
                        stack = persistentListOf(),
                        exile = persistentListOf(),
                    ),
                definitions = MvpCards.definitions.toPersistentMap(),
            )
        }

        "CR 605.1b: the seat controlling a Forest enchanted by an opponent's Utopia Sprawl may float mana" {
            val state = sprawlAcrossTheTable(aliceMana = emptyList(), bobMana = listOf(ManaType.GREEN))

            InvariantChecker.checkManaPoolEmptiness(state).shouldBeEmpty()
        }

        "CR 605.1b: the Aura's controller gains no exemption from a Sprawl on an opponent's Forest" {
            // Alice controls the Aura but not the enchanted land, so the mana never reaches her pool;
            // floating mana in it is still engine wrongness and must still be reported.
            val state = sprawlAcrossTheTable(aliceMana = listOf(ManaType.GREEN), bobMana = emptyList())

            InvariantChecker.checkManaPoolEmptiness(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.MANA_POOL_EMPTY_AT_PAUSE)
        }

        "CR 704.5m: an Aura attached to nothing adds no mana and so grants no exemption" {
            val unattachedSprawl = GameObject(ObjectId(51), CardRef("Utopia Sprawl"), alice)
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(library = mountains(0L..2L, alice))
                            .copy(manaPool = persistentListOf(ManaType.GREEN)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                ).copy(
                    sharedZones =
                        SharedZones(
                            battlefield = persistentListOf(unattachedSprawl),
                            stack = persistentListOf(),
                            exile = persistentListOf(),
                        ),
                    definitions = MvpCards.definitions.toPersistentMap(),
                )

            InvariantChecker.checkManaPoolEmptiness(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.MANA_POOL_EMPTY_AT_PAUSE)
        }

        // --- TAP_STATUS_SCOPE ----------------------------------------------------------------

        "CR 110.5: a tapped object outside the battlefield is exactly one TAP_STATUS_SCOPE violation" {
            val residences =
                listOf(
                    ZoneResidence(
                        ZoneId.Hand(alice),
                        GameObject(ObjectId(1), CardRef("Mountain"), alice, tapped = true),
                    ),
                )
            InvariantChecker.checkTapStatusScope(residences).map { it.invariant } shouldContainExactly
                listOf(Invariant.TAP_STATUS_SCOPE)
        }

        "tap-status scope: a tapped battlefield object produces no violation" {
            val residences =
                listOf(
                    ZoneResidence(
                        ZoneId.Battlefield,
                        GameObject(ObjectId(1), CardRef("Mountain"), alice, tapped = true),
                    ),
                )
            InvariantChecker.checkTapStatusScope(residences).shouldBeEmpty()
        }

        // --- LAND_DROP_BOUND -----------------------------------------------------------------

        "CR 305.2: a land-drop count above one is exactly one LAND_DROP_BOUND violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain.copy(landsPlayedThisTurn = 2),
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.LAND_DROP_BOUND)
        }

        "land-drop bound: a count of one — the normal used drop — produces no violation" {
            val state =
                twoPlayerState(
                    turn = precombatMain.copy(landsPlayedThisTurn = 1),
                    aliceState = playerWithZones(library = mountains(0L..2L, alice)),
                    bobState = playerWithZones(library = mountains(10L..12L, bob)),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state).shouldBeEmpty()
        }

        // --- clean multi-invariant coverage -----------------------------------------------

        "a well-formed state with a baseline reports no violations at all" {
            val state =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState =
                        playerWithZones(
                            library = mountains(0L..5L, alice),
                            hand = mountains(6L..12L, alice),
                        ),
                    bobState =
                        playerWithZones(
                            library = mountains(20L..25L, bob),
                            hand = mountains(26L..32L, bob),
                        ),
                    nextObjectId = 100,
                )
            InvariantChecker.check(state, CardCensus.of(state)).shouldBeEmpty()
            // A lone-state check (no baseline) also finds nothing, and skips only card conservation.
            InvariantChecker.check(state) shouldBe emptyList()
        }

        // --- MARKED_DAMAGE_SCOPE -----------------------------------------------------------

        "CR 120.3d: marked damage off the battlefield is exactly one MARKED_DAMAGE_SCOPE violation" {
            val marked = GameObject(ObjectId(1), CardRef("Bear"), alice, damageMarked = 2)
            val residences = listOf(ZoneResidence(ZoneId.Graveyard(alice), marked))
            checkMarkedDamageScope(residences).map { it.invariant } shouldContainExactly
                listOf(Invariant.MARKED_DAMAGE_SCOPE)
        }

        "marked damage on the battlefield is within scope and clean" {
            val marked = GameObject(ObjectId(1), CardRef("Bear"), alice, damageMarked = 2)
            checkMarkedDamageScope(listOf(ZoneResidence(ZoneId.Battlefield, marked))).shouldBeEmpty()
        }

        "CR 704.5h: a deathtouch record off the battlefield is exactly one MARKED_DAMAGE_SCOPE violation" {
            // The record describes marked damage, so it shares its scope: an object reborn in a
            // graveyard carries neither (CR 400.7). Two violations, one per property, is the honest
            // count — the corrupt object really does break both.
            val marked =
                GameObject(ObjectId(1), CardRef("Bear"), alice, damageMarked = 2, dealtDeathtouchDamage = true)
            checkMarkedDamageScope(listOf(ZoneResidence(ZoneId.Graveyard(alice), marked)))
                .map { it.invariant } shouldContainExactly
                listOf(Invariant.MARKED_DAMAGE_SCOPE, Invariant.MARKED_DAMAGE_SCOPE)
        }

        "a deathtouch record on the battlefield is within scope and clean" {
            val marked =
                GameObject(ObjectId(1), CardRef("Bear"), alice, damageMarked = 1, dealtDeathtouchDamage = true)
            checkMarkedDamageScope(listOf(ZoneResidence(ZoneId.Battlefield, marked))).shouldBeEmpty()
        }

        // --- COMBAT_REFERENCES_VALID -------------------------------------------------------

        "CR 508.1: a combat attacker not on the battlefield is one COMBAT_REFERENCES_VALID violation" {
            val state =
                combatState(
                    battlefield = emptyList(),
                    combat = CombatState(attackers = persistentListOf(AttackerAssignment(ObjectId(99), bob))),
                )
            checkCombatReferences(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.COMBAT_REFERENCES_VALID)
        }

        "CR 509.1: a combat blocker not on the battlefield is one COMBAT_REFERENCES_VALID violation" {
            // The block's attacker is a real, declared attacker, so CombatState construction accepts
            // it; only the checker's battlefield cross-reference catches the phantom blocker.
            val giant = GameObject(ObjectId(1), CardRef("Giant"), alice)
            val state =
                combatState(
                    battlefield = listOf(giant),
                    combat =
                        CombatState(
                            attackers = persistentListOf(AttackerAssignment(ObjectId(1), bob)),
                            blocks = persistentListOf(BlockAssignment(ObjectId(99), ObjectId(1))),
                        ),
                )
            checkCombatReferences(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.COMBAT_REFERENCES_VALID)
        }

        "a combat referencing only real battlefield creatures with a valid order is clean" {
            val giant = GameObject(ObjectId(1), CardRef("Giant"), alice)
            val bear = GameObject(ObjectId(2), CardRef("Bear"), bob)
            val ogre = GameObject(ObjectId(3), CardRef("Ogre"), bob)
            val state =
                combatState(
                    battlefield = listOf(giant, bear, ogre),
                    combat =
                        CombatState(
                            attackers = persistentListOf(AttackerAssignment(ObjectId(1), bob)),
                            blocks =
                                persistentListOf(
                                    BlockAssignment(ObjectId(2), ObjectId(1)),
                                    BlockAssignment(ObjectId(3), ObjectId(1)),
                                ),
                            blockerOrder = persistentMapOf(ObjectId(1) to persistentListOf(ObjectId(2), ObjectId(3))),
                        ),
                )
            checkCombatReferences(state).shouldBeEmpty()
        }

        "no combat in progress yields no COMBAT_REFERENCES_VALID violation" {
            checkCombatReferences(combatState(battlefield = emptyList(), combat = null)).shouldBeEmpty()
        }

        // --- CREATURE_LETHALITY_RESOLVED ---------------------------------------------------

        "CR 704.5g: a battlefield creature with lethal marked damage is one CREATURE_LETHALITY_RESOLVED violation" {
            // Grizzly Bears is 2/2; 2 marked damage is lethal, so at a pause it should already have
            // died — a lingering one means the death state-based action failed to run (CR 704.3).
            val bears = GameObject(ObjectId(1), CardRef("Grizzly Bears"), alice, damageMarked = 2)
            checkCreatureLethalityResolved(lethalityState(listOf(bears))).map { it.invariant } shouldContainExactly
                listOf(Invariant.CREATURE_LETHALITY_RESOLVED)
        }

        "creature lethality: sublethal marked damage on a battlefield creature is clean" {
            val bears = GameObject(ObjectId(1), CardRef("Grizzly Bears"), alice, damageMarked = 1)
            checkCreatureLethalityResolved(lethalityState(listOf(bears))).shouldBeEmpty()
        }

        "CR 704.5h: a battlefield creature carrying a deathtouch record is one CREATURE_LETHALITY_RESOLVED violation" {
            // Grizzly Bears is 2/2 and the damage is sublethal, so CR 704.5g is clean and only
            // CR 704.5h makes this a lingering death — which is the whole point of the separate rule.
            val bears =
                GameObject(
                    ObjectId(1),
                    CardRef("Grizzly Bears"),
                    alice,
                    damageMarked = 1,
                    dealtDeathtouchDamage = true,
                )
            checkCreatureLethalityResolved(lethalityState(listOf(bears))).map { it.invariant } shouldContainExactly
                listOf(Invariant.CREATURE_LETHALITY_RESOLVED)
        }

        "CR 702.12b: an indestructible creature is exempt from both destruction conditions, never a violation" {
            // A Bridge is an indestructible *land*, so the only indestructible creature this checker can
            // meet is a granted one — Tamiyo's Safekeeping's shape. It may sit at a pause with lethal
            // marked damage and a deathtouch record and be entirely correct (CR 702.12b), which is why
            // the exemption had to be stated the moment deathtouch became grantable.
            val warded =
                GameObject(
                    ObjectId(1),
                    CardRef("Grizzly Bears"),
                    alice,
                    damageMarked = 5,
                    dealtDeathtouchDamage = true,
                    counters = persistentMapOf(Counter.KeywordCounter(Keyword.INDESTRUCTIBLE) to 1),
                )
            checkCreatureLethalityResolved(lethalityState(listOf(warded))).shouldBeEmpty()
        }

        "creature lethality: a definitionless creature card is inert and never a violation" {
            // No registry entry: the card is inert (architect decision, P2.1), not a creature to the
            // checker, so its marked damage cannot make it a lingering-death violation.
            val phantom = GameObject(ObjectId(1), CardRef("Grizzly Bears"), alice, damageMarked = 9)
            checkCreatureLethalityResolved(lethalityState(listOf(phantom), withDefinitions = false)).shouldBeEmpty()
        }

        // --- PENDING_RESOLUTION_SANITY -----------------------------------------------------

        "CR 608.1: a resolution-discard pause with an empty stack is one PENDING_RESOLUTION_SANITY violation" {
            // A "draw N, then discard M" pause hangs on the resolving spell; an empty stack means it is gone.
            val paused =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(),
                    bobState = playerWithZones(),
                    nextObjectId = 1,
                ).copy(pendingResolutionDiscard = PendingResolutionDiscard(alice, 1))
            checkPendingResolutionSanity(paused).map { it.invariant } shouldContainExactly
                listOf(Invariant.PENDING_RESOLUTION_SANITY)
        }

        "pending-resolution sanity: no open resolution pause produces no violation" {
            val clean =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(),
                    bobState = playerWithZones(),
                    nextObjectId = 1,
                )
            checkPendingResolutionSanity(clean).shouldBeEmpty()
        }

        // --- ABILITY_TARGET_SANITY ---------------------------------------------------------

        "CR 601.2c: an untargeted ability carrying a target is one ABILITY_TARGET_SANITY violation" {
            val entry =
                StackEntry.Ability(
                    PendingTrigger(ObjectId(0), CardRef("Fixture"), alice, untargetedAbility()),
                    persistentListOf(Target.Player(bob)),
                )
            checkAbilityTargetSanity(abilityStackState(entry)).map { it.invariant } shouldContainExactly
                listOf(Invariant.ABILITY_TARGET_SANITY)
        }

        "CR 603.3d: a triggered ability on the stack with no legal target found is NOT a violation" {
            // The load-bearing asymmetry: a trigger goes on the stack target-less when its controller
            // had no legal choice, and CR 608.2b removes it later. Only an *activated* ability may not.
            val entry =
                StackEntry.Ability(
                    PendingTrigger(ObjectId(0), CardRef("Fixture"), alice, targetingAbility()),
                    persistentListOf(),
                )
            checkAbilityTargetSanity(abilityStackState(entry)).shouldBeEmpty()
        }

        "CR 601.2c: an activated ability on the stack with no target is one ABILITY_TARGET_SANITY violation" {
            val entry =
                StackEntry.ActivatedAbilityOnStack(
                    sourceId = ObjectId(0),
                    sourceCard = CardRef("Fixture"),
                    controller = alice,
                    ability =
                        ActivatedAbility(
                            cost = persistentListOf(AbilityCost.TapSelf),
                            targetSpec = TargetSpec.TargetOpponent,
                            effect = ResolutionEffect { state, _ -> state },
                        ),
                )
            checkAbilityTargetSanity(abilityStackState(entry)).map { it.invariant } shouldContainExactly
                listOf(Invariant.ABILITY_TARGET_SANITY)
        }

        "CR 603.3d: a trigger-targeting pause whose trigger does not target is one violation" {
            val paused =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(),
                    bobState = playerWithZones(),
                    nextObjectId = 1,
                ).copy(
                    pendingTriggers =
                        persistentListOf(PendingTrigger(ObjectId(0), CardRef("Fixture"), alice, untargetedAbility())),
                    pendingTriggerTargets = PendingTriggerTargets(alice, ObjectId(0), CardRef("Fixture")),
                )
            checkAbilityTargetSanity(paused).map { it.invariant } shouldContainExactly
                listOf(Invariant.ABILITY_TARGET_SANITY)
        }

        "ability-target sanity: a well-formed targeting trigger and its pause produce no violation" {
            val trigger = PendingTrigger(ObjectId(0), CardRef("Fixture"), alice, targetingAbility())
            val paused =
                twoPlayerState(
                    turn = precombatMain,
                    aliceState = playerWithZones(),
                    bobState = playerWithZones(),
                    nextObjectId = 1,
                ).copy(
                    pendingTriggers = persistentListOf(trigger),
                    pendingTriggerTargets = PendingTriggerTargets(alice, ObjectId(0), CardRef("Fixture")),
                )
            checkAbilityTargetSanity(paused).shouldBeEmpty()
        }

        // --- ENTRY_TRIGGER_DETECTION -------------------------------------------------------

        "CR 603.6a: a land that entered with its trigger lost is exactly one ENTRY_TRIGGER_DETECTION violation" {
            // The T18 state itself: the log says the land arrived, and nothing anywhere says its
            // enters-the-battlefield ability ever fired. This is the shape no other invariant can see.
            val violations = checkEntryTriggerDetection(entryState(GameEvent.LandPlayed(alice, ObjectId(0), ETB_LAND)))

            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ENTRY_TRIGGER_DETECTION)
        }

        "CR 603.6a: the same land with its trigger put on the stack is clean" {
            val clean =
                entryState(
                    GameEvent.LandPlayed(alice, ObjectId(0), ETB_LAND),
                    GameEvent.TriggeredAbilityPutOnStack(alice, ETB_LAND),
                )
            checkEntryTriggerDetection(clean).shouldBeEmpty()
        }

        "CR 603.3b: a trigger still pending counts as fired — it has not reached the stack yet, but it will" {
            val pending =
                entryState(GameEvent.LandPlayed(alice, ObjectId(0), ETB_LAND))
                    .copy(
                        pendingTriggers =
                            persistentListOf(PendingTrigger(ObjectId(0), ETB_LAND, alice, entryAbility())),
                    )
            checkEntryTriggerDetection(pending).shouldBeEmpty()
        }

        "CR 111.4: a created token whose enters-the-battlefield trigger was lost violates too — T18's twin" {
            val violations =
                checkEntryTriggerDetection(entryState(GameEvent.TokenCreated(alice, ObjectId(0), ETB_LAND)))

            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ENTRY_TRIGGER_DETECTION)
        }

        "CR 603.6a: a trigger fired with no entry to account for it violates in the other direction" {
            // The fifth-path risk: an object reached the battlefield announcing nothing, so its
            // trigger is on the stack with no entry event to justify it.
            val violations =
                checkEntryTriggerDetection(entryState(GameEvent.TriggeredAbilityPutOnStack(alice, ETB_LAND)))

            violations.map { it.invariant } shouldContainExactly listOf(Invariant.ENTRY_TRIGGER_DETECTION)
        }

        "CR 603.6a: an empty log and a card that never entered produce no violation" {
            checkEntryTriggerDetection(entryState()).shouldBeEmpty()
        }
    })

/** The fixture card the entry-trigger checks measure: one enters-the-battlefield ability, nothing else. */
private val ETB_LAND: CardRef = CardRef("Fixture Entry Land")

/** The fixture's single enters-the-battlefield ability (CR 603.6a). */
private fun entryAbility(): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = ResolutionEffect { state, _ -> state },
    )

/**
 * A handcrafted state whose registry holds [ETB_LAND] — a card with exactly one
 * enters-the-battlefield ability — and whose event log is [events]. The entry-trigger invariant is a
 * property of the log and the registry, so both are supplied directly; a real engine transition
 * cannot produce the losing log at all now that the defect is fixed, which is the point of building it
 * by hand (the same reason zone conservation is violated through extracted data).
 */
private fun entryState(vararg events: GameEvent): GameState =
    GameState(
        players = persistentMapOf(alice to playerWithZones(), bob to playerWithZones()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(), persistentListOf()),
        nextObjectId = 1,
        rng = Rng(0),
        events = events.toList().toPersistentList(),
        definitions = persistentMapOf(ETB_LAND to entryLandDefinition),
    )

/** The registered definition of [ETB_LAND]: a land whose only triggered ability is its entry trigger. */
private val entryLandDefinition: CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = ETB_LAND.name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.LAND),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val triggeredAbilities = persistentListOf(entryAbility())
    }

/** A fixture triggered ability that targets nothing. */
private fun untargetedAbility(): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        effect = ResolutionEffect { state, _ -> state },
    )

/** A fixture triggered ability that targets an opponent (CR 115.1a). */
private fun targetingAbility(): TriggeredAbility =
    TriggeredAbility(
        condition = TriggerCondition.EnteredBattlefieldSelf,
        targetSpec = TargetSpec.TargetOpponent,
        effect = ResolutionEffect { state, _ -> state },
    )

/** A two-player state whose stack holds the single ability [entry], for the ability-target checks. */
private fun abilityStackState(entry: StackEntry): GameState =
    GameState(
        players = persistentMapOf(alice to playerWithZones(), bob to playerWithZones()),
        turn = Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(persistentListOf(), persistentListOf(entry), persistentListOf()),
        nextObjectId = 1,
        rng = Rng(0),
        events = persistentListOf(),
    )

// A handcrafted state at the declare-attackers step with the given battlefield and (optional)
// combat, for the combat-reference checks. The combat may reference ids the battlefield lacks —
// which is exactly what checkCombatReferences catches, and what construction cannot.
private fun combatState(
    battlefield: List<GameObject>,
    combat: CombatState?,
): GameState {
    val nextId = (battlefield.maxOfOrNull { it.id.value }?.plus(1)) ?: 1L
    return GameState(
        players = persistentMapOf(alice to playerWithZones(), bob to playerWithZones()),
        turn = Turn(alice, 3, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS, combat = combat),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
    )
}

// A handcrafted main-phase state with the given battlefield, for the creature-lethality check.
// [withDefinitions] carries the real MvpCards registry (so a Grizzly Bears is a known 2/2 creature)
// or none (so the same card is inert and unrecognised as a creature).
private fun lethalityState(
    battlefield: List<GameObject>,
    withDefinitions: Boolean = true,
): GameState =
    GameState(
        players = persistentMapOf(alice to playerWithZones(), bob to playerWithZones()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = (battlefield.maxOfOrNull { it.id.value }?.plus(1)) ?: 1L,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = if (withDefinitions) MvpCards.definitions.toPersistentMap() else persistentMapOf(),
    )
