package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.engine.LayeredCharacteristics
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The eight Bogles Auras (CR 303) — P4.2's seven plus P6.3's [lifelink]: their printed characteristics
 * against the oracle card
 * (CR 201–205), each card's static continuous effect computing the right layered P/T, keyword, or
 * mana-ability grant on a real enchanted object (CR 613, layers 6 and 7c), and the named multi-aura
 * timestamp scenarios of docs/design/layer-system.md §8. Enchant-restriction *legality* through the
 * real cast pipeline, and the Abundant-Growth off-color payment, live in the acceptance module (they
 * drive the engine); these specs assert the definition data and the layered computation directly
 * through the public [layeredCharacteristics].
 */
class AurasSpec :
    StringSpec({

        "CR 303 and CR 205.3: each Aura is an Enchantment — Aura with its printed cost and enchant restriction" {
            data class Expected(
                val name: String,
                val cost: String,
                val restriction: EnchantRestriction,
            )

            val auras =
                listOf(
                    rancor to Expected("Rancor", "{G}", EnchantRestriction.CREATURE),
                    armadilloCloak to Expected("Armadillo Cloak", "{1}{G}{W}", EnchantRestriction.CREATURE),
                    cartoucheOfSolidarity to
                        Expected("Cartouche of Solidarity", "{W}", EnchantRestriction.CREATURE_YOU_CONTROL),
                    sentinelsEyes to Expected("Sentinel's Eyes", "{W}", EnchantRestriction.CREATURE),
                    etherealArmor to Expected("Ethereal Armor", "{W}", EnchantRestriction.CREATURE),
                    ancestralMask to Expected("Ancestral Mask", "{2}{G}", EnchantRestriction.CREATURE),
                    abundantGrowth to Expected("Abundant Growth", "{G}", EnchantRestriction.LAND),
                    lifelink to Expected("Lifelink", "{W}", EnchantRestriction.CREATURE),
                )
            auras.forEach { (definition, expected) ->
                with(definition.characteristics) {
                    name shouldBe expected.name
                    manaCost?.render() shouldBe expected.cost
                    cardTypes.shouldContainExactlyInAnyOrder(CardType.ENCHANTMENT)
                    subtypes.shouldContainExactlyInAnyOrder(Subtype("Aura"))
                    powerToughness shouldBe null
                }
                // A single static ability's continuous effect, targeting its enchant restriction (CR 303.4a).
                definition.staticContinuousEffects.size shouldBe 1
                definition.targetSpec
                    .shouldBeInstanceOf<TargetSpec.Enchantable>()
                    .restriction shouldBe expected.restriction
            }
        }

        "CR 613 sublayer 7c and layer 6: each fixed-bonus Aura buffs a real enchanted creature correctly" {
            data class Expected(
                val name: String,
                val power: Int,
                val toughness: Int,
                val keywords: Set<Keyword>,
            )

            // Grizzly Bears is a 2/2 (CR 208.1); each fixed Aura adds its printed +X/+Y and keyword.
            val cases =
                listOf(
                    Expected("Rancor", 4, 2, setOf(Keyword.TRAMPLE)),
                    Expected("Armadillo Cloak", 4, 4, setOf(Keyword.TRAMPLE)),
                    Expected("Cartouche of Solidarity", 3, 3, setOf(Keyword.FIRST_STRIKE)),
                    Expected("Sentinel's Eyes", 3, 3, setOf(Keyword.VIGILANCE)),
                )
            cases.forEach { expected ->
                val state = enchantedBears(listOf(expected.name))
                val layered = layeredCharacteristics(state, ObjectId(0))
                layered.power shouldBe expected.power
                layered.toughness shouldBe expected.toughness
                layered.keywords.shouldContainExactlyInAnyOrder(*expected.keywords.toTypedArray())
            }
        }

        "CR 702.15 / CR 613 layer 6: the card named Lifelink grants the lifelink keyword and nothing else" {
            // Current Oracle: "Enchanted creature has lifelink" — a pure layer-6 keyword grant, no P/T
            // change and no triggered ability (unlike Armadillo Cloak's damage trigger).
            val state = enchantedBears(listOf("Lifelink"))
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.power shouldBe 2
            layered.toughness shouldBe 2
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.LIFELINK)
            lifelink.triggeredAbilities.shouldBeEmpty()
        }

        "CR 702.15 / CR 603.2: Lifelink and Armadillo Cloak stack — a keyword grant plus a damage trigger" {
            // The pair docs/decklists.md calls out: the Cloak's lifegain is a triggered ability that
            // uses the stack, Lifelink's is a result of the damage. A creature wearing both has the
            // keyword *and* the trigger, so one damage event gains its controller the amount twice.
            val state = enchantedBears(listOf("Lifelink", "Armadillo Cloak"))
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.LIFELINK, Keyword.TRAMPLE)
            armadilloCloak.triggeredAbilities.size shouldBe 1
            lifelink.triggeredAbilities.shouldBeEmpty()
        }

        "CR 613.3c: Ethereal Armor is +N/+N and first strike, N = enchantments you control (itself counts)" {
            // Bears + one Ethereal Armor attached: N = 1 (the Armor itself) -> +1/+1 -> 3/3.
            val state = enchantedBears(listOf("Ethereal Armor"))
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.power shouldBe 3
            layered.toughness shouldBe 3
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)
        }

        "CR 613.3c: Ancestral Mask is +2/+2 per OTHER enchantment on the battlefield" {
            // Bears + Ancestral Mask attached, and one other enchantment elsewhere (an Abundant Growth
            // on a Forest). Mask sees exactly 1 other enchantment -> +2/+2 -> Bears is 4/4. No keyword.
            val state =
                enchantedBears(
                    auraNames = listOf("Ancestral Mask"),
                    extra =
                        listOf(
                            bfObject(10, "Forest", alice),
                            bfObject(11, "Abundant Growth", alice, attachedTo = 10),
                        ),
                )
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.power shouldBe 4
            layered.toughness shouldBe 4
            layered.keywords.shouldBeEmpty()
        }

        "CR 613 layer 6 and CR 605.1a: Abundant Growth grants an enchanted land '{T}: add one mana of any color'" {
            // A Forest ({T}: add {G}) enchanted by Abundant Growth gains the any-color mana ability.
            val forestId = ObjectId(0)
            val state =
                boardState(
                    listOf(
                        bfObject(0, "Forest", alice),
                        bfObject(1, "Abundant Growth", alice, attachedTo = 0),
                    ),
                )
            val layered = layeredCharacteristics(state, forestId)
            // The land is not a creature: no P/T box (layer 7c never invents one).
            layered.power shouldBe null
            layered.toughness shouldBe null
            // Printed {T}: add {G}, then the layer-6 grant of the WUBRG any-color ability.
            layered.manaAbilities shouldContainExactly
                listOf(
                    ManaAbility(persistentListOf(ManaType.GREEN)),
                    ManaAbility(
                        persistentListOf(
                            ManaType.WHITE,
                            ManaType.BLUE,
                            ManaType.BLACK,
                            ManaType.RED,
                            ManaType.GREEN,
                        ),
                    ),
                )
        }

        "§8: two Ethereal Armors on one creature -> +4/+4 total, first strike once, attach order irrelevant" {
            // Each Armor counts BOTH as enchantments you control (N = 2) -> +2/+2 each -> +4/+4 total.
            val ordered = bearsLayeredWith("Ethereal Armor", "Ethereal Armor")
            ordered.power shouldBe 6
            ordered.toughness shouldBe 6
            // First strike is a set union, so two grants leave exactly one first strike.
            ordered.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)

            // Attach order (the timestamp order the two Armors entered) is irrelevant: additive 7c
            // modifiers and set-union layer-6 grants commute (docs/design/layer-system.md §3). Both
            // orderings are the same two cards, so the swap is by id-assignment order.
            val swapped = bearsLayeredWith("Ethereal Armor", "Ethereal Armor")
            swapped.power shouldBe ordered.power
            swapped.toughness shouldBe ordered.toughness
        }

        "§8: Ethereal Armor + Ancestral Mask on one creature -> +4/+4 total, attach-order-independent" {
            // Ethereal Armor sees two enchantments you control (Armor + Mask) -> +2/+2.
            // Ancestral Mask sees one OTHER enchantment (the Armor) -> +2/+2. Total +4/+4 -> 6/6.
            val armorFirst = bearsLayeredWith("Ethereal Armor", "Ancestral Mask")
            armorFirst.power shouldBe 6
            armorFirst.toughness shouldBe 6
            armorFirst.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)

            // Reverse the attach order (swap which id each Aura gets): identical result (CR 613.7/613.8
            // commute for the additive pool, so the deferred timestamp is not observable — §3).
            val maskFirst = bearsLayeredWith("Ancestral Mask", "Ethereal Armor")
            maskFirst.power shouldBe armorFirst.power
            maskFirst.toughness shouldBe armorFirst.toughness
            maskFirst.keywords shouldBe armorFirst.keywords
        }

        "CR 613.3c and §8: a new enchantment entering elsewhere recounts Ethereal Armor with no explicit recompute" {
            // Bears + one Ethereal Armor: N = 1 -> +1/+1 -> 3/3.
            val before = enchantedBears(listOf("Ethereal Armor"))
            layeredCharacteristics(before, ObjectId(0)).let {
                it.power shouldBe 3
                it.toughness shouldBe 3
            }

            // A second enchantment enters the battlefield elsewhere (Abundant Growth on a Forest, both
            // controlled by alice). N jumps to 2 purely because the board changed -> +2/+2 -> 4/4.
            val after =
                enchantedBears(
                    auraNames = listOf("Ethereal Armor"),
                    extra =
                        listOf(
                            bfObject(10, "Forest", alice),
                            bfObject(11, "Abundant Growth", alice, attachedTo = 10),
                        ),
                )
            layeredCharacteristics(after, ObjectId(0)).let {
                it.power shouldBe 4
                it.toughness shouldBe 4
            }
        }

        "an opponent's enchantment does not raise Ethereal Armor's 'you control' count" {
            // Bears + alice's Ethereal Armor (N counts alice's enchantments = 1 -> 3/3), plus an
            // enchantment bob controls elsewhere, which must not be counted (control is ownership, §4).
            val state =
                enchantedBears(
                    auraNames = listOf("Ethereal Armor"),
                    extra =
                        listOf(
                            bfObject(10, "Forest", bob),
                            bfObject(11, "Abundant Growth", bob, attachedTo = 10),
                        ),
                )
            layeredCharacteristics(state, ObjectId(0)).power shouldBe 3
        }
    })

private val alice = PlayerId(0)
private val bob = PlayerId(1)

/** A battlefield [GameObject] over [MvpCards]: [name] resolves via the registry; [attachedTo] is an id. */
private fun bfObject(
    id: Long,
    name: String,
    owner: PlayerId,
    attachedTo: Long? = null,
): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(name),
        owner = owner,
        attachedTo = attachedTo?.let(::ObjectId),
    )

/** A handcrafted main-phase two-player [GameState] over [MvpCards] with [battlefield] in place. */
private fun boardState(battlefield: List<GameObject>): GameState {
    fun seat() = PlayerState(20, persistentListOf(), persistentListOf(), persistentListOf())
    val nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}

/**
 * A state where a Grizzly Bears (id 0, alice's) is enchanted, in [auraNames] order (ids 1..n), by the
 * named Auras, plus any [extra] battlefield objects. The auras' ids follow their list order, so a
 * reversed list is the attach-order swap the §8 order-independence scenarios assert on.
 */
private fun enchantedBears(
    auraNames: List<String>,
    extra: List<GameObject> = emptyList(),
): GameState {
    val bears = bfObject(0, "Grizzly Bears", alice)
    val auras = auraNames.mapIndexed { index, name -> bfObject((index + 1).toLong(), name, alice, attachedTo = 0) }
    return boardState(listOf(bears) + auras + extra)
}

/** The layered characteristics of a Grizzly Bears enchanted, in order, by the named Auras (id 0). */
private fun bearsLayeredWith(vararg auraNames: String): LayeredCharacteristics =
    layeredCharacteristics(enchantedBears(auraNames.toList()), ObjectId(0))
