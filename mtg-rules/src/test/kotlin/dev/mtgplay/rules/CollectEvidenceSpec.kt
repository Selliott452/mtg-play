package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EachOpponentSacrifices
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeNarrowing
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOpponentSacrifice
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.engine.evidenceManaValue
import dev.mtgplay.rules.engine.initialOptionalCostAnnouncement
import dev.mtgplay.rules.engine.optionalCostIsPayable
import dev.mtgplay.rules.engine.optionalCostPayableWith
import dev.mtgplay.rules.engine.pendingOpponentSacrificeRequest
import dev.mtgplay.rules.engine.updatePlayer
import dev.mtgplay.rules.engine.validateDecision
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The two `W9-B` frameworks, exercised where a card definition cannot show them: the **collect evidence**
 * option set and its summed validation (CR 601.2b, CR 701.60a), and the **each-opponent-sacrifices**
 * clause's greatest-power narrowing (CR 701.17a).
 *
 * These are pinned here rather than left to `EnumerationProbe` for `NonManaCastCostSpec`'s reason: the
 * probe replays each candidate and catches an *over*-enumeration, but an option never offered is never
 * probed. Both failures this file guards are under-enumerations — a graveyard card silently excluded
 * from the evidence list, or a tied top-power creature silently dropped from an edict's choice — and
 * each deletes a legal line without anything throwing (ADR-005).
 */
class CollectEvidenceSpec :
    StringSpec({
        val evidence = OptionalAdditionalCost.CollectEvidence(6)

        "CR 202.3: an evidence option's weight is its printed mana value, and a land's is zero" {
            val state = evidenceState(aliceGraveyard = listOf("Angler", "Swamp", "Bear"))

            state.player(alice).graveyard.map { evidenceManaValue(state, it) } shouldBe listOf(7, 0, 2)
        }

        "CR 701.60a: the option list is the whole graveyard, in graveyard order and unfiltered" {
            val state = evidenceState(aliceGraveyard = listOf("Swamp", "Bear", "Angler"))

            // Every card can go toward the total — a zero-mana-value land included, because which cards
            // leave is the caster's choice and a land is a perfectly reasonable thing to keep or spend.
            optionalCostPayableWith(state, alice, evidence).map { it.card.name } shouldBe
                listOf("Swamp", "Bear", "Angler")
            // The opponent's graveyard is not the caster's to exile (CR 701.60a: "your graveyard").
            optionalCostPayableWith(state, bob, evidence).shouldBeEmpty()
        }

        "CR 601.2b: the announcement is gated on the graveyard's total, not on its emptiness" {
            // Four lands: a long non-empty option list that pays nothing. Offering a "yes" here would
            // open a selection stage with no legal answer — the enumerated-then-unpayable defect.
            val lands = evidenceState(aliceGraveyard = List(4) { "Swamp" })
            optionalCostPayableWith(lands, alice, evidence).size shouldBe 4
            optionalCostIsPayable(lands, alice, evidence) shouldBe false
            initialOptionalCostAnnouncement(lands, alice, evidenceSpell()) shouldBe false

            // One 7-drop pays evidence 6 on its own: the size of a legal answer is not bounded below
            // by anything, which is exactly why this is not a sized selection.
            val angler = evidenceState(aliceGraveyard = listOf("Angler"))
            optionalCostIsPayable(angler, alice, evidence) shouldBe true
            initialOptionalCostAnnouncement(angler, alice, evidenceSpell()) shouldBe null

            // Exactly six is enough — "6 or greater" is inclusive.
            val exact = evidenceState(aliceGraveyard = List(3) { "Bear" })
            optionalCostIsPayable(exact, alice, evidence) shouldBe true
        }

        "CR 701.60a: any subset reaching the total is legal, whatever its size" {
            val request = evidenceRequest()

            // One 7-drop, or three 2-drops: both pay, and neither size is privileged.
            validateDecision(request, Decision.MultiSelect(request.id, listOf(0)))
            validateDecision(request, Decision.MultiSelect(request.id, listOf(1, 2, 3)))
            // Over-paying is legal too, and is a real line: which cards leave matters to a player
            // holding back a flashback card, so the engine must not force the minimum.
            validateDecision(request, Decision.MultiSelect(request.id, listOf(0, 1, 2, 3, 4)))
        }

        "CR 701.60a: a subset that does not reach the total is rejected, and so is the empty one" {
            val request = evidenceRequest()

            // Two 2-drops total 4. Declining is the *announcement's* enumerated index one stage
            // earlier, so an empty answer here would be a cost half-paid rather than a cost refused.
            shouldThrow<IllegalArgumentException> {
                validateDecision(request, Decision.MultiSelect(request.id, listOf(1, 2)))
            }
            shouldThrow<IllegalArgumentException> {
                validateDecision(request, Decision.MultiSelect(request.id, emptyList()))
            }
            // A repeated index would let one card pay twice; distinctness is checked as for any subset.
            shouldThrow<IllegalArgumentException> {
                validateDecision(request, Decision.MultiSelect(request.id, listOf(1, 1, 1)))
            }
        }

        "CR 701.60a: the request refuses to exist over a graveyard that cannot pay it" {
            shouldThrow<IllegalArgumentException> {
                DecisionRequest.ChooseEvidence(
                    id = DecisionRequestId(alice, 0),
                    cardObjectId = ObjectId(GRAVEYARD_ID_BASE),
                    card = CardRef("Confession"),
                    options = listOf(DecisionRequest.ChooseEvidence.Option(ObjectId(1), CardRef("Swamp"), 0)),
                    requiredTotal = 6,
                )
            }
        }

        "CR 701.17a: without the linked cost, every creature the opponent controls is offered" {
            val request = sacrificeRequest(optionalCostPaid = false)

            request.greatestPowerOnly shouldBe false
            request.options.map { it.card.name } shouldBe listOf("Bear", "Angler", "Ogre")
            // The controller's own creatures are never in an *opponent's* option list.
            request.seat shouldBe bob
            request.controller shouldBe alice
        }

        "CR 701.17a: with the cost paid the list narrows to the greatest power - and still asks" {
            val request = sacrificeRequest(optionalCostPaid = true)

            request.greatestPowerOnly shouldBe true
            // Angler is 5/5 and Ogre is 5/5; Bear is 2/2. Two creatures tie at the top, and which of
            // them dies is a real decision — collapsing it to an engine pick would delete a line, and
            // offering the Bear would enumerate an illegal one.
            request.options.map { it.card.name } shouldBe listOf("Angler", "Ogre")
        }
    })

// ---- fixtures ----

/** A `{1}{B}` spell printing collect evidence 6 and the each-opponent-sacrifices clause. */
private fun evidenceSpell(): SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Confession",
                manaCost = ManaCost.parse("{1}{B}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED
        override val targetSpec = TargetSpec.None
        override val resolution = ResolutionEffect { state, _ -> state }
        override val optionalAdditionalCost = OptionalAdditionalCost.CollectEvidence(6)
        override val eachOpponentSacrifices =
            EachOpponentSacrifices(
                cardType = CardType.CREATURE,
                narrowing = SacrificeNarrowing.ANY,
                narrowingWhenOptionalCostPaid = SacrificeNarrowing.GREATEST_POWER,
            )
    }

/** A card fixture with a real printed mana cost, so its mana value is something other than zero. */
private fun priced(
    name: String,
    cost: String?,
    power: Int?,
    toughness: Int?,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = cost?.let(ManaCost::parse),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(if (power == null) CardType.LAND else CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness =
                    if (power == null || toughness == null) null else PrintedPowerToughness(power, toughness),
            )
    }

private val evidenceDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        priced("Swamp", null, null, null),
        priced("Bear", "{1}{B}", 2, 2),
        priced("Ogre", "{4}{B}", 5, 5),
        priced("Angler", "{6}{B}", 5, 5),
        priced("Confession", "{1}{B}", null, null),
    ).associateBy { CardRef(it.characteristics.name) }

/** A paused state whose seats hold [aliceGraveyard] / [bobBattlefield], over the priced fixtures. */
private fun evidenceState(
    aliceGraveyard: List<String> = emptyList(),
    bobBattlefield: List<String> = emptyList(),
): GameState {
    val field =
        bobBattlefield.mapIndexed { index, name -> GameObject(ObjectId(index.toLong()), CardRef(name), bob) }
    val base = keywordState(field).copy(definitions = evidenceDefinitions.toPersistentMap())
    // Graveyard ids start well past the battlefield and library ones keywordState allocated, and the
    // counter is moved past them all: object ids are unique across every zone (CR 400.7).
    val graveyard =
        aliceGraveyard.mapIndexed { index, name ->
            GameObject(ObjectId(GRAVEYARD_ID_BASE + index), CardRef(name), alice)
        }
    return base
        .copy(nextObjectId = GRAVEYARD_ID_BASE + graveyard.size + STACK_ID_HEADROOM)
        .updatePlayer(alice) { seat -> seat.copy(graveyard = graveyard.toPersistentList()) }
}

/** The first object id this file's graveyard fixtures use — clear of every id `keywordState` hands out. */
private const val GRAVEYARD_ID_BASE: Long = 100L

/** Room above the graveyard ids for the resolving spell object the sacrifice fixture puts on the stack. */
private const val STACK_ID_HEADROOM: Long = 10L

/** A collect-evidence 6 request over one 7-drop and four 2-drops (indices 0 and 1..4). */
private fun evidenceRequest(): DecisionRequest.ChooseEvidence =
    DecisionRequest.ChooseEvidence(
        id = DecisionRequestId(alice, 0),
        cardObjectId = ObjectId(GRAVEYARD_ID_BASE),
        card = CardRef("Confession"),
        options =
            listOf(DecisionRequest.ChooseEvidence.Option(ObjectId(1), CardRef("Angler"), 7)) +
                (2..5).map { DecisionRequest.ChooseEvidence.Option(ObjectId(it.toLong()), CardRef("Bear"), 2) },
        requiredTotal = 6,
    )

/**
 * The request an open each-opponent-sacrifice pause surfaces, over bob's Bear (2/2), Angler (5/5) and
 * Ogre (5/5), with alice's spell resolving on the stack having (or not having) paid its evidence.
 */
private fun sacrificeRequest(optionalCostPaid: Boolean): DecisionRequest.ChooseOpponentSacrifice {
    val definition = evidenceSpell()
    val state = evidenceState(bobBattlefield = listOf("Bear", "Angler", "Ogre"))
    val spellObject = GameObject(ObjectId(GRAVEYARD_ID_BASE + STACK_ID_HEADROOM - 1), CardRef("Confession"), alice)
    val entry =
        StackEntry.Spell(
            obj = spellObject,
            controller = alice,
            targets = persistentListOf(),
            definition = definition,
            optionalCostPaid = optionalCostPaid,
        )
    val clause = checkNotNull(definition.eachOpponentSacrifices)
    val narrowing = if (optionalCostPaid) clause.narrowingWhenOptionalCostPaid else clause.narrowing
    val narrowed = narrowing == SacrificeNarrowing.GREATEST_POWER
    val paused =
        state.copy(
            sharedZones = state.sharedZones.copy(stack = persistentListOf(entry)),
            pendingOpponentSacrifice =
                PendingOpponentSacrifice(
                    decider = bob,
                    controller = alice,
                    greatestPowerOnly = narrowed,
                    remaining = persistentListOf(),
                    sourceCard = CardRef("Confession"),
                ),
        )
    return pendingOpponentSacrificeRequest(paused)
}

/** The seat's state, for readability in the assertions above. */
private fun GameState.player(id: PlayerId) = players.getValue(id)
