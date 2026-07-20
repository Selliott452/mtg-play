package dev.mtgplay.core.card

/**
 * A subtype (CR 205.3): one word from the subtype line, e.g. a creature type ("Beast"), a land
 * type ("Forest", "Mountain"), or an enchantment type ("Aura").
 *
 * Modeled as a value class over the exact printed word rather than an enum: the space of
 * subtypes is huge and grows with every set. Land types are load-bearing in the MVP pool —
 * Utopia Sprawl enchants a Forest, and Fireblast's alternative cost sacrifices Mountains.
 *
 * @property value the exact subtype word, e.g. `"Forest"`; never blank.
 */
@JvmInline
value class Subtype(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "subtype must not be blank" }
    }
}
