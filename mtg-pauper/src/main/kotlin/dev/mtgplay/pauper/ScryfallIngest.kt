package dev.mtgplay.pauper

import dev.mtgplay.core.mana.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the staged Scryfall snapshot into a [CardCatalog] (P6.1 ingestion).
 *
 * Reads the JSON through kotlinx.serialization's [JsonElement] tree API only — no `@Serializable`
 * model and so no serialization compiler plugin (a flagged build decision, packet report). Every
 * field is extracted with a loud, explicitly-typed accessor: a missing field, a wrong JSON type,
 * or an unrecognised enumerated value fails with a message naming the card and field, never a
 * silent default (CONVENTIONS.md: fail loudly, never approximate).
 */
object ScryfallIngest {
    /** The JSON key holding the array of card objects. */
    private const val CARDS_KEY = "cards"

    /** The JSON key holding the snapshot's attribution/provenance string. */
    private const val SOURCE_KEY = "source"

    /**
     * Parses [json] — the whole snapshot document — into a [CardCatalog]. Fails loudly on any
     * malformed structure or value.
     */
    fun parse(json: String): CardCatalog {
        val root = Json.parseToJsonElement(json).asObject("<root>")
        val cards = root.array(CARDS_KEY, "<root>").mapIndexed { index, element -> parseCard(element, index) }
        val attribution = root.string(SOURCE_KEY, "<root>")
        return CardCatalog(cards = cards, attribution = attribution)
    }

    /** Parses one card object at [index] (for the failure message before its name is known). */
    private fun parseCard(
        element: JsonElement,
        index: Int,
    ): CardMetadata {
        val where = "cards[$index]"
        val card = element.asObject(where)
        val name = card.string("name", where)
        return CardMetadata(
            name = name,
            manaCost = card.string("mana_cost", name),
            typeLine = card.string("type_line", name),
            oracleText = card.string("oracle_text", name),
            power = card.stringOrNull("power", name),
            toughness = card.stringOrNull("toughness", name),
            colors = parseColors(card.array("colors", name), name),
            pauperLegality = Legality.ofScryfall(card.obj("legalities", name).string("pauper", name)),
            oracleId = card.string("oracle_id", name),
        )
    }

    /** Maps Scryfall's color letters (`W`/`U`/`B`/`R`/`G`) to [Color], failing loudly on any other. */
    private fun parseColors(
        colors: JsonArray,
        card: String,
    ): Set<Color> =
        colors
            .map { element ->
                val letter = element.asStringPrimitive(card, "colors")
                Color.entries.firstOrNull { it.letter.toString() == letter }
                    ?: error("card \"$card\" has an unrecognised color \"$letter\"")
            }.toSet()

    private fun JsonElement.asObject(where: String): JsonObject =
        this as? JsonObject ?: error("$where is not a JSON object")

    private fun JsonElement.asStringPrimitive(
        card: String,
        field: String,
    ): String {
        val primitive = this as? JsonPrimitive ?: error("card \"$card\" field \"$field\" is not a string")
        require(primitive.isString) { "card \"$card\" field \"$field\" is not a string: $primitive" }
        return primitive.content
    }

    private fun JsonObject.member(
        key: String,
        card: String,
    ): JsonElement = this[key] ?: error("card \"$card\" is missing the \"$key\" field")

    private fun JsonObject.string(
        key: String,
        card: String,
    ): String = member(key, card).asStringPrimitive(card, key)

    /** A string field that may be JSON `null` (an absent power/toughness) — `null`, not a default. */
    private fun JsonObject.stringOrNull(
        key: String,
        card: String,
    ): String? {
        val value = member(key, card)
        return if (value is JsonNull) null else value.asStringPrimitive(card, key)
    }

    private fun JsonObject.obj(
        key: String,
        card: String,
    ): JsonObject = member(key, card) as? JsonObject ?: error("card \"$card\" field \"$key\" is not a JSON object")

    private fun JsonObject.array(
        key: String,
        card: String,
    ): JsonArray = member(key, card) as? JsonArray ?: error("card \"$card\" field \"$key\" is not a JSON array")
}
