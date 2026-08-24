package dev.mtgplay.protocol

import dev.mtgplay.cards.warriorToken
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.PrintedCardView
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.viewFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The wire contract for the seat view's card table (ADR-007 + ADR-008): the public printed
 * characteristics of the cards a seat can see, keyed by printed name, with the CR 111 token fact.
 *
 * The type-line vocabularies cross the wire as their exact words rather than as mirrored DTO enums
 * (see `PrintedCardDtos.kt`), so the exhaustiveness guarantee is a **test** over every member of each
 * vocabulary rather than a compile-time `when`: a new [CardType]/[Supertype]/[Keyword]/[Evasion]
 * member is covered here the moment it is declared.
 */
class PrintedCardDtoSpec :
    StringSpec({
        "ADR-008: every member of every printed-characteristic vocabulary round-trips on the wire" {
            val probe = vocabularyProbe()
            val dto = probe.toDto()

            // Coverage is asserted against the engine enums themselves, so a new member is included here
            // automatically instead of silently missing from a hand-written mapping table.
            dto.supertypes.size shouldBe Supertype.entries.size
            dto.cardTypes.size shouldBe CardType.entries.size
            dto.keywords.size shouldBe Keyword.entries.size
            dto.evasions.size shouldBe Evasion.entries.size
            dto.toDomain() shouldBe probe
        }

        "ADR-008: a printed card view round-trips through the strict codec unchanged" {
            val view = PrintedCardView(characteristics = vocabularyProbe(), isToken = false)

            val json = ProtocolJson.encodeToString(view.toDto())
            ProtocolJson.decodeFromString<PrintedCardViewDto>(json).toDomain() shouldBe view
        }

        "CR 111: a token's characteristics and its token fact survive the wire" {
            val view = PrintedCardView(characteristics = warriorToken.characteristics, isToken = true)

            val json = ProtocolJson.encodeToString(view.toDto())
            json shouldContain "\"isToken\":true"
            ProtocolJson.decodeFromString<PrintedCardViewDto>(json).toDomain() shouldBe view
        }

        "ADR-007/ADR-008: a seat view carrying a token round-trips with its whole card table" {
            val view = tokenSeatView()
            val tokenRef = CardRef(warriorToken.characteristics.name)

            // The table is on the view before it is on the wire, and the token is marked as one (CR 111).
            view.cards.getValue(tokenRef).isToken shouldBe true

            val json = ProtocolJson.encodeToString(view.toDto())
            json shouldContain "\"${warriorToken.characteristics.name}\""
            val back = ProtocolJson.decodeFromString<SeatViewDto>(json).toDomain()
            back shouldBe view
            back.cards.getValue(tokenRef) shouldBe view.cards.getValue(tokenRef)
        }

        "ADR-008: a vocabulary word this schema does not know is rejected loudly, never dropped" {
            val dto = vocabularyProbe().toDto().copy(cardTypes = listOf("CREATURE", "NOT_A_CARD_TYPE"))

            val failure = shouldThrow<IllegalArgumentException> { dto.toDomain() }
            failure.message shouldContain "NOT_A_CARD_TYPE"
        }

        "ADR-008: a card with no mana cost round-trips as an absent cost, never as {0}" {
            val land =
                PrintedCharacteristics(
                    name = "Wire Land",
                    manaCost = null,
                    supertypes = persistentSetOf(Supertype.BASIC),
                    cardTypes = persistentSetOf(CardType.LAND),
                    subtypes = persistentSetOf(Subtype("Mountain")),
                    powerToughness = null,
                )

            land.toDto().manaCost shouldBe null
            land.toDto().toDomain() shouldBe land
        }
    })

/**
 * A single card carrying **every** member of each closed printed-characteristic vocabulary, so one
 * round-trip covers them all. It names every card type, which includes [CardType.CREATURE], so it
 * carries a printed power/toughness box (CR 208.1).
 */
private fun vocabularyProbe(): PrintedCharacteristics =
    PrintedCharacteristics(
        name = "Wire Vocabulary Probe",
        manaCost = ManaCost.parse("{2}{G/U}{R/P}{C}"),
        supertypes = Supertype.entries.toPersistentSet(),
        cardTypes = CardType.entries.toPersistentSet(),
        subtypes = persistentSetOf(Subtype("Forest"), Subtype("Warrior")),
        powerToughness = PrintedPowerToughness(3, 4),
        keywords = Keyword.entries.toPersistentSet(),
        evasions = Evasion.entries.toPersistentSet(),
    )

/**
 * A seat view of a battlefield holding one real predefined token (CR 111.4), registered under its
 * name-[CardRef] exactly as the create-token primitive registers it.
 */
private fun tokenSeatView(): SeatView {
    val tokenRef = CardRef(warriorToken.characteristics.name)
    val token = GameObject(id = ObjectId(1), card = tokenRef, owner = PlayerId(0))
    val empty =
        PlayerState(
            life = 20,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
        )
    val state =
        GameState(
            players = persistentMapOf(PlayerId(0) to empty, PlayerId(1) to empty),
            turn = Turn(activePlayer = PlayerId(0), number = 1, phase = TurnPhase.PRECOMBAT_MAIN, step = null),
            sharedZones =
                SharedZones(
                    battlefield = persistentListOf(token),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = 2,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = persistentMapOf(tokenRef to warriorToken),
        )
    return viewFor(state, PlayerId(0))
}
