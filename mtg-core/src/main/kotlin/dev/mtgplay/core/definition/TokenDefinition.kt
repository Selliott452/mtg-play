package dev.mtgplay.core.definition

import dev.mtgplay.core.card.PrintedCharacteristics
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * A predefined token's definition (CR 111.4): the [CardDefinition] a token created on the battlefield
 * uses for its characteristics. Additive, flagged core (P5.1).
 *
 * **Tokens are not cards (CR 111).** A token is a game object created directly on the battlefield by
 * an effect, never drawn or cast, and it carries no card in any library. Identity treatment (P5.1
 * design): a token flows through the whole engine as an ordinary battlefield [GameObject] whose
 * `card` reference resolves to a [TokenDefinition] in the state's definition registry — so combat, the
 * layer system, and the state-based actions all read its characteristics through the same
 * `definitions[card]` path a real card uses, with **no** new object field. "This object is a token"
 * is therefore `definitions[card] is TokenDefinition`: stable across the CR 400.7 rebirths (the
 * printed reference is conserved, so a token reborn in the graveyard is still recognizably a token),
 * which is exactly what the CR 704.5d "token in a non-battlefield zone ceases to exist" state-based
 * action and the acceptance card-census (which excludes tokens from the conserved multiset) key on.
 *
 * The token's [dev.mtgplay.core.identity.CardRef] is its name; the create-token rules primitive
 * registers this definition under that ref if absent, so the token's characteristics ride in
 * [dev.mtgplay.core.state.GameState] like every other definition (ADR-009). A token is not castable,
 * so this is a plain [CardDefinition], never a [SpellDefinition].
 *
 * @property characteristics the token's printed characteristics (CR 111.4): its name, types,
 *   power/toughness, and keywords — e.g. the 1/1 white Warrior creature token with vigilance.
 * @property manaAbilities the token's intrinsic mana abilities (CR 605.1a); empty for a Warrior token,
 *   non-empty for a mana-producing token (Eldrazi Spawn, P6).
 * @property staticContinuousEffects the token's static continuous effects; empty in the MVP pool.
 * @property triggeredAbilities the token's triggered abilities; empty in the MVP pool.
 * @property activatedAbilities the token's activated abilities (CR 602); empty for most tokens, non-empty
 *   for the Blood token, whose "{1}, {T}, Discard a card, Sacrifice this token: Draw a card" (CR 602.1) is
 *   read through the same `definitions[card].activatedAbilities` path a real card's ability uses. Additive,
 *   flagged core (P6.2c) — the one field [manaAbilities] and the others were missing, so a token could not
 *   carry a non-mana activated ability until now.
 */
data class TokenDefinition(
    override val characteristics: PrintedCharacteristics,
    override val manaAbilities: PersistentList<ManaAbility> = persistentListOf(),
    override val staticContinuousEffects: PersistentList<StaticContinuousEffect> = persistentListOf(),
    override val triggeredAbilities: PersistentList<TriggeredAbility> = persistentListOf(),
    override val activatedAbilities: PersistentList<ActivatedAbility> = persistentListOf(),
) : CardDefinition
