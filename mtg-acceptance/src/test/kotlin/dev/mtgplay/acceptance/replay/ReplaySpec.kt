package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.acceptance.driver.Responders
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.mountainConfig
import dev.mtgplay.acceptance.mountains
import dev.mtgplay.acceptance.playerWithZones
import dev.mtgplay.acceptance.twoPlayerState
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.PendingLibrarySearch
import dev.mtgplay.core.state.PendingOptionalCostDraw
import dev.mtgplay.core.state.PendingResolutionDiscard
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.MatchConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf

/**
 * Replay and fingerprinting (ADR-006): the same `(config, decisions)` reproduces a game exactly on
 * two independent axes — the rules-relevant state fingerprint and the derived event log — and the
 * fingerprint is stable, event-independent, and sensitive to rules-relevant change.
 */
class ReplaySpec :
    StringSpec({

        "ADR-006: the same config driven the same way twice fingerprints identically and logs identically" {
            val first = ScriptedGame.start(mountainConfig()).playToCompletion(Responders.PASS_AND_DISCARD_LOWEST)
            val second = ScriptedGame.start(mountainConfig()).playToCompletion(Responders.PASS_AND_DISCARD_LOWEST)
            fingerprint(second.state) shouldBe fingerprint(first.state)
            second.state.events shouldBe first.state.events
            second.decisions shouldBe first.decisions
        }

        "ADR-006: a decision log recorded by the scripted driver replays to the same fingerprint and log" {
            val original = ScriptedGame.start(mountainConfig()).playToCompletion(Responders.PASS_AND_DISCARD_LOWEST)
            val outcome = ReplayHarness.verifyReproduces(mountainConfig(), original)
            outcome.reproduced.shouldBeTrue()
            outcome.fingerprintMatches.shouldBeTrue()
            outcome.eventLogMatches.shouldBeTrue()
        }

        "ADR-006 sanity: different seeds shuffle a distinguishable deck into different orders" {
            val mixedDeck = List(30) { CardRef("Mountain") } + List(30) { CardRef("Forest") }

            fun mixedConfig(seed: Long) =
                MatchConfig(
                    seed = seed,
                    libraries = mapOf(alice to mixedDeck, bob to mixedDeck),
                    startingPlayer = alice,
                    mulligansEnabled = false,
                )
            val gameA = ScriptedGame.start(mixedConfig(seed = 1))
            val gameB = ScriptedGame.start(mixedConfig(seed = 2))
            val libraryA =
                gameA.state.players
                    .getValue(alice)
                    .library
                    .map { it.card }
            val libraryB =
                gameB.state.players
                    .getValue(alice)
                    .library
                    .map { it.card }
            libraryB shouldNotBe libraryA
            fingerprint(gameB.state) shouldNotBe fingerprint(gameA.state)
        }

        "the fingerprint excludes the event log: two states differing only in events fingerprint alike" {
            val base =
                twoPlayerState(
                    turn = Turn(alice, 2, TurnPhase.PRECOMBAT_MAIN, null),
                    aliceState = playerWithZones(library = mountains(0L..5L, alice)),
                    bobState = playerWithZones(library = mountains(10L..15L, bob)),
                    nextObjectId = 100,
                )
            val withEvent = base.copy(events = persistentListOf(GameEvent.PriorityPassed(alice)))
            fingerprint(withEvent) shouldBe fingerprint(base)
        }

        "the fingerprint is sensitive to a rules-relevant change: a differing life total differs" {
            val turn = Turn(alice, 2, TurnPhase.PRECOMBAT_MAIN, null)
            val library = mountains(0L..5L, alice)
            val opponent = playerWithZones(library = mountains(10L..15L, bob))
            val healthy =
                twoPlayerState(turn, playerWithZones(life = 20, library = library), opponent, nextObjectId = 100)
            val hurt =
                twoPlayerState(turn, playerWithZones(life = 19, library = library), opponent, nextObjectId = 100)
            fingerprint(hurt) shouldNotBe fingerprint(healthy)
        }

        "the fingerprint digests the attachment cause: states differing only in attachedTo differ (P4.1)" {
            val turn = Turn(alice, 2, TurnPhase.PRECOMBAT_MAIN, null)
            val base = twoPlayerState(turn, playerWithZones(), playerWithZones(), nextObjectId = 100)

            fun withAttachment(attachedTo: ObjectId?) =
                base.copy(
                    sharedZones =
                        SharedZones(
                            battlefield =
                                persistentListOf(
                                    GameObject(ObjectId(0), CardRef("Grizzly Bears"), alice),
                                    GameObject(ObjectId(1), CardRef("Rancor"), alice, attachedTo = attachedTo),
                                ),
                            stack = persistentListOf(),
                            exile = persistentListOf(),
                        ),
                )
            // The attachment cause is in the digest, so the same board with a different attachment
            // fingerprints apart — how continuous-effect differences are told apart (§5).
            fingerprint(withAttachment(ObjectId(0))) shouldNotBe fingerprint(withAttachment(null))
        }

        "the fingerprint digests the P6.2c mid-resolution pauses (cost-then-draw, resolution discard, search)" {
            val base =
                twoPlayerState(
                    turn = Turn(alice, 2, TurnPhase.PRECOMBAT_MAIN, null),
                    aliceState = playerWithZones(library = mountains(0L..5L, alice)),
                    bobState = playerWithZones(library = mountains(10L..15L, bob)),
                    nextObjectId = 100,
                )
            // Optional cost-then-draw (Highway Robbery): present vs absent, and mode-chosen vs mode-pending.
            val modePending = base.copy(pendingOptionalCostDraw = PendingOptionalCostDraw(alice))
            val objectPending =
                base.copy(pendingOptionalCostDraw = PendingOptionalCostDraw(alice, OptionalCostMode.DiscardCard))
            fingerprint(modePending) shouldNotBe fingerprint(base)
            fingerprint(objectPending) shouldNotBe fingerprint(modePending)
            // Mandatory resolution discard (Faithless Looting): present vs absent, and differing counts.
            fingerprint(base.copy(pendingResolutionDiscard = PendingResolutionDiscard(alice, 1))) shouldNotBe
                fingerprint(base)
            fingerprint(base.copy(pendingResolutionDiscard = PendingResolutionDiscard(alice, 2))) shouldNotBe
                fingerprint(base.copy(pendingResolutionDiscard = PendingResolutionDiscard(alice, 1)))
            // Library search (Ash Barrens): present vs absent.
            fingerprint(base.copy(pendingLibrarySearch = PendingLibrarySearch(alice))) shouldNotBe fingerprint(base)
        }
    })
