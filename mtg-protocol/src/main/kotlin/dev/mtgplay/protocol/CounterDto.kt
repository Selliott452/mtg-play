package dev.mtgplay.protocol

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.state.Counter
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one entry in a permanent's counter multiset (CR 122.1): the kind of counter and how
 * many of it the permanent has. Added by `FW-COUNTERS`.
 *
 * **Counters on a permanent are public information** (ADR-007) — every player can see them across
 * the table — so they ride in [GameObjectDto] unfiltered, exactly like the tapped status and marked
 * damage, and no seat-specific redaction applies.
 *
 * A JSON *list* rather than a map, because the multiset's key is a structured value and a JSON object
 * demands a string key; stringifying `+1/+1` would invent a text format nothing else in the schema
 * has and would have to be parsed back. The list preserves the engine's deterministic map order.
 */
@Serializable
sealed interface CounterDto {
    /** How many counters of this kind the permanent has (CR 122.1); always at least one. */
    val count: Int

    /** A `+X/+Y` counter (CR 122.1a); `-1/-1` is `power = -1, toughness = -1`. */
    @Serializable
    @SerialName("power_toughness")
    data class PowerToughness(
        val power: Int,
        val toughness: Int,
        override val count: Int,
    ) : CounterDto

    /** A keyword counter (CR 122.1b), the keyword named by its enum constant name. */
    @Serializable
    @SerialName("keyword")
    data class KeywordCounter(
        val keyword: String,
        override val count: Int,
    ) : CounterDto

    /**
     * A charge counter (CR 122.1): an inert marker whose meaning is written on the permanent carrying
     * it, not on the counter. Added by `W10-C`; a Spacecraft's Station counters.
     *
     * It carries [count] and nothing else — there is no discriminator field, because
     * [dev.mtgplay.core.state.Counter.Charge] is a singleton on the engine side. A named-counter wire
     * form arrives with the first card that puts two different inert kinds on one permanent.
     */
    @Serializable
    @SerialName("charge")
    data class Charge(
        override val count: Int,
    ) : CounterDto
}

/** A counter multiset to its wire form, preserving the engine's deterministic entry order. */
fun PersistentMap<Counter, Int>.toDto(): List<CounterDto> =
    map { (kind, count) ->
        when (kind) {
            is Counter.PowerToughness -> CounterDto.PowerToughness(kind.power, kind.toughness, count)
            is Counter.KeywordCounter -> CounterDto.KeywordCounter(kind.keyword.name, count)
            Counter.Charge -> CounterDto.Charge(count)
        }
    }

/** A wire counter list back to the engine multiset; fails loudly on an unknown keyword. */
fun List<CounterDto>.toDomain(): PersistentMap<Counter, Int> =
    associate { dto ->
        when (dto) {
            is CounterDto.PowerToughness -> Counter.PowerToughness(dto.power, dto.toughness) to dto.count
            is CounterDto.KeywordCounter -> Counter.KeywordCounter(keywordNamed(dto.keyword)) to dto.count
            is CounterDto.Charge -> Counter.Charge to dto.count
        }
    }.toPersistentMap()

/** The [Keyword] named [word] on the wire; a word this engine does not know is a hard error (ADR-008). */
private fun keywordNamed(word: String): Keyword =
    Keyword.entries.firstOrNull { it.name == word }
        ?: error("unknown keyword '$word' on the wire; the peer's vocabulary does not match this protocol version")
