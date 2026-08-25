package dev.mtgplay.protocol

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Counters on the wire (CR 122.1, ADR-007/ADR-008): a permanent's counters are public information,
 * so they ride in [GameObjectDto] unredacted, and they survive the strict [ProtocolJson] codec
 * unchanged. The `6.0.0` half of the protocol bump `FW-COUNTERS` records.
 */
class CounterDtoSpec :
    StringSpec({

        fun permanent(counters: Map<Counter, Int>) =
            GameObject(
                id = ObjectId(7),
                card = CardRef("Grizzly Bears"),
                owner = PlayerId(0),
                counters = counters.toPersistentMap(),
            )

        "CR 122.1a/122.1b: a permanent's counters round-trip through the strict codec" {
            val obj =
                permanent(
                    mapOf(
                        Counter.PLUS_ONE_PLUS_ONE to 3,
                        Counter.MINUS_ZERO_MINUS_ONE to 1,
                        Counter.KeywordCounter(Keyword.LIFELINK) to 1,
                    ),
                )
            val json = ProtocolJson.encodeToString(obj.toDto())
            ProtocolJson.decodeFromString<GameObjectDto>(json).toDomain() shouldBe obj
        }

        "ADR-008: the two counter kinds carry stable discriminators" {
            val json =
                ProtocolJson.encodeToString(
                    permanent(
                        mapOf(
                            Counter.PLUS_ONE_PLUS_ONE to 1,
                            Counter.KeywordCounter(Keyword.LIFELINK) to 1,
                        ),
                    ).toDto(),
                )
            json shouldContain "\"type\":\"power_toughness\""
            json shouldContain "\"type\":\"keyword\""
            json shouldContain "\"keyword\":\"LIFELINK\""
        }

        "CR 122.2: a permanent with no counters serialises an empty list, not a null" {
            val obj = permanent(emptyMap())
            obj.toDto().counters.shouldBeEmpty()
            ProtocolJson
                .decodeFromString<GameObjectDto>(ProtocolJson.encodeToString(obj.toDto()))
                .toDomain() shouldBe obj
        }

        "the protocol version records the keyword-tail break" {
            // `FW-COUNTERS` made GameObjectDto.counters required, which took the wire to 6.0.0.
            // `FW-MANACOST` broke it again and harder, taking it to 7.0.0: PaymentPlanDto's
            // SourceClassKeyDto.profile changes element type, loses `viaSacrifice`, and
            // ManaActivationDto gains a required `costPayment` — a both-directions break in the most
            // frequent request in a match.
            // The keyword-tail packet breaks it twice more, which is why this is 8.0.0 rather than
            // folded into 7.0.0: GameObjectDto gains a **required** `dealtDeathtouchDamage`
            // (CR 704.5h), so a strict 7.0.0 codec rejects every seat view; and Keyword gains
            // DEATHTOUCH and CHANGELING while Evasion gains BLOCKABLE_ONLY_BY_HASTE, all of which
            // ride in PrintedCardDto's strict `parseVocabulary` and so fail at *runtime*, mid-match.
            // `W8-A` breaks it three more ways, all of them required-field additions the server sends:
            // LibraryArrangementDto gains `toGraveyard` (surveil's fourth destination, CR 701.44a), so a
            // strict 8.0.0 codec rejects *every* look decision and not only a surveil's; SeatViewDto gains
            // `pendingOptionalTrigger` (CR 603.2); and PendingColorChoiceDto gains `playedLand`, because a
            // CR 614.12 choice can now interrupt the play-land special action as well as a resolving
            // spell. No DecisionRequest kind is added — all three decisions reuse existing requests.
            // `W8-D` takes it to 9.0.0, and for the sharpest reason in the chain so far: it *changes*
            // an existing payload rather than adding to one. SacrificeRequirementDto's `subtype: String`
            // becomes `filter: SacrificeFilterDto` (Dread Return's flashback sacrifices three
            // **creatures**, a card type), and that DTO rides inside every priority window offering a
            // flashback or alternative-cost cast — so an 8.0.0 peer and a 9.0.0 peer disagree about a
            // message they both already know how to send. Three new DecisionRequest kinds and a new
            // CastingPermissionDto discriminator ride along.
            // Pinned here so no bump in the chain can be quietly reverted.
            PROTOCOL_VERSION shouldBe "9.0.0"
        }
    })
