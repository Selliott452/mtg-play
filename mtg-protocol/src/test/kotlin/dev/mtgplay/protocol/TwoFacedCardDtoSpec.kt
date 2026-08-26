package dev.mtgplay.protocol

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.rules.StackEntryView
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The `W10-B` wire additions (protocol `11.0.0`): the two two-faced [CastingPermissionDto]
 * discriminators, the stack view's `castAsFace`, and the [GameObjectDto] adventure marker.
 *
 * **Round-tripping is the property that shaped the design**, so it is what these tests pin. A face is a
 * whole second [dev.mtgplay.core.definition.SpellDefinition] whose resolution and targeting are function
 * values; a permission carrying one could be sent and never reconstructed, which is why the permission
 * carries only the face's cost and name and the face itself lives on the card. Every assertion below is
 * a check that the reconstruction is exact.
 */
class TwoFacedCardDtoSpec :
    StringSpec({

        "CR 715.3: the adventure permission carries the face's name as well as its cost, and round-trips" {
            val adventure = CastingPermission.Adventure(ManaCost.parse("{1}{R}"), "Forktail Sweep")
            val json = ProtocolJson.encodeToString<CastingPermissionDto>(adventure.toDto())
            json shouldBe """{"type":"adventure","cost":"{1}{R}","faceName":"Forktail Sweep"}"""
            ProtocolJson.decodeFromString<CastingPermissionDto>(json).toDomain() shouldBe adventure
        }

        "CR 720.3: the omen permission is its twin on the wire, and the discriminator is the difference" {
            val omen = CastingPermission.Omen(ManaCost.parse("{G}"), "Sagu Wilds")
            val json = ProtocolJson.encodeToString<CastingPermissionDto>(omen.toDto())
            json shouldBe """{"type":"omen","cost":"{G}","faceName":"Sagu Wilds"}"""
            ProtocolJson.decodeFromString<CastingPermissionDto>(json).toDomain() shouldBe omen
            // The two payloads are identical bar the discriminator, and that is the only thing telling a
            // peer whether choosing the option banks the card in exile or shuffles it away (CR 715.3d /
            // CR 720.3d) — so a codec that dropped the discriminator would confuse two different cards.
            (adventureJson() == json) shouldBe false
        }

        "ADR-005: a face cast rides inside a priority option and comes back naming the same half" {
            // The whole point of `faceName`: both options carry the same card ref (CR 715.2c — an
            // adventurer card is one card), so without it a peer sees "Cast Fang Dragon" twice.
            val normal = PriorityOption.CastSpell(ObjectId(7), CardRef("Fang Dragon"))
            val faced =
                PriorityOption.CastSpell(
                    ObjectId(7),
                    CardRef("Fang Dragon"),
                    CastSource.HAND,
                    CastingPermission.Adventure(ManaCost.parse("{1}{R}"), "Forktail Sweep"),
                )
            listOf(normal, faced).forEach { option ->
                val json = ProtocolJson.encodeToString<PriorityOptionDto>(option.toDto())
                ProtocolJson.decodeFromString<PriorityOptionDto>(json).toDomain() shouldBe option
            }
            (normal.toDto() == faced.toDto()) shouldBe false
        }

        "CR 405: the stack view publishes which half is on the stack, and round-trips it" {
            val sweeping =
                StackEntryView.SpellOnStack(
                    objectId = ObjectId(9),
                    card = CardRef("Fang Dragon"),
                    controller = PlayerId(0),
                    targets = emptyList(),
                    castAsFace = "Forktail Sweep",
                )
            sweeping.toDto().toDomain() shouldBe sweeping
            val json = ProtocolJson.encodeToString<StackEntryViewDto>(sweeping.toDto())
            ProtocolJson.decodeFromString<StackEntryViewDto>(json).toDomain() shouldBe sweeping
            // Absent for every spell cast normally, so a single-faced board's payload does not move.
            StackEntryView
                .SpellOnStack(ObjectId(10), CardRef("Grizzly Bears"), PlayerId(0), emptyList())
                .toDto()
                .let { it as StackEntryViewDto.SpellOnStack }
                .castAsFace shouldBe null
        }

        "CR 715.3d: the adventure exile marker rides on every game object and round-trips" {
            val onAdventure =
                GameObject(ObjectId(11), CardRef("Fang Dragon"), PlayerId(0)).copy(onAnAdventure = true)
            onAdventure.toDto().onAnAdventure shouldBe true
            onAdventure.toDto().toDomain() shouldBe onAdventure
            GameObject(ObjectId(12), CardRef("Grizzly Bears"), PlayerId(0)).toDto().onAnAdventure shouldBe false
        }
    })

private fun adventureJson(): String =
    ProtocolJson.encodeToString<CastingPermissionDto>(
        CastingPermission.Adventure(ManaCost.parse("{G}"), "Sagu Wilds").toDto(),
    )
