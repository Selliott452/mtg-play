package dev.mtgplay.core.identity

/**
 * The stable printed-object identity a game object carries across its whole life, and the key its
 * definition is registered under.
 *
 * Where an [ObjectId] is reborn on every zone change (CR 400.7), a [CardRef] is what stays
 * constant: the printed object that a whole sequence of objects originates from. It is
 * name-based for now (the exact oracle name); Scryfall ids arrive with the ingestion work in
 * Phase 6.
 *
 * **A token's ref is not a card name, and that is CR 111.1 rather than a decoration** (`FW-COPYTOKEN`).
 * A token is *not a card*, so it has no card name to be keyed by; its characteristics are defined by
 * the effect that created it (CR 111.4). Until this framework, tokens were keyed by their name and
 * nothing else, which was harmless only for as long as no token shared a name with a card. A **copy**
 * token does exactly that — an embalm token named "Sacred Cat" lands on the registry entry the real
 * Sacred Cat occupies — and the create-token primitive's register-if-absent would then have handed the
 * token the *card's* definition: castable, embalmable again, and invisible to the CR 704.5d
 * token-ceases state-based action. Marking the ref is what keeps the two identities apart.
 *
 * **Why the mark rides in the string rather than in a second field.** A [CardRef] is a
 * `@JvmInline value class` over one string, and that string is also the key of the seat view's
 * `cards` table and the value every protocol payload carries a card as. A boolean beside the name
 * would collide in that JSON object (two entries, one key) and be dropped by every one of the sixty
 * places that send a ref as a bare name — a remote seat would reconstruct the token as the card. A
 * distinguished *key* round-trips losslessly through all of them with no wire change at all.
 *
 * **[name] is the key, not the object's name characteristic.** The two coincide for a card and
 * deliberately do not for a token: the token's CR 201.2 name is
 * `definition.characteristics.name`, which stays "Sacred Cat". Every rules read of an object's *name*
 * must go through the definition; this property is for identity and display only.
 *
 * @property name the registry key: for a card, the exact printed (oracle) card name; for a token, that
 *   name followed by [TOKEN_MARKER]. Never blank.
 */
@JvmInline
value class CardRef(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "card name must not be blank" }
    }

    /**
     * Whether this ref names a **token** rather than a card (CR 111.1).
     *
     * The engine's older test, `definitions[card] is TokenDefinition`, still works and still answers the
     * same question; this is the answer available without the registry, and the two are kept in
     * agreement by [dev.mtgplay.core.state.GameState]'s own registration invariant.
     */
    val isToken: Boolean get() = name.endsWith(TOKEN_MARKER)

    /**
     * The **name characteristic** of the object this ref identifies (CR 201.2): the key with any token
     * mark stripped. Equal to [name] for a card, and equal to the copied card's name for a copy token —
     * which is what makes an embalm token really a permanent named "Sacred Cat".
     */
    val printedName: String get() = name.removeSuffix(TOKEN_MARKER)

    companion object {
        /**
         * The suffix distinguishing a token's registry key from a card name. No Magic card name ends in
         * it, and the engine's own registration invariant refuses a card definition registered under a
         * marked ref, so the two spaces cannot overlap by accident.
         */
        const val TOKEN_MARKER: String = " (token)"

        /**
         * The registry key of the token named [name] (CR 111.1). The one way to build a token ref, so a
         * token can never be registered under a bare card name by a caller that forgot.
         */
        fun token(name: String): CardRef {
            require(!name.endsWith(TOKEN_MARKER)) {
                "CR 111.1: a token name is marked once; \"$name\" already carries the token marker"
            }
            return CardRef(name + TOKEN_MARKER)
        }
    }
}
