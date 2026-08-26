package dev.mtgplay.protocol

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.PendingCascade
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The `W9-G` wire additions (protocol `10.0.0`): the two new [CastingPermissionDto] discriminators, the
 * [GameObjectDto] prototype marker, and [PendingCascadeDto].
 *
 * The prototype permission is the first whose payload is not purely a cost, and the test pins the whole
 * encoded object rather than a substring for exactly that reason: a peer that rendered only `cost` would
 * show a `{3}{G}` Boulderbranch Golem still reading 6/5, which is not a card.
 */
class AlternateCastingDtoSpec :
    StringSpec({

        "CR 718.2: the prototype permission carries its size as well as its cost, and round-trips" {
            val prototype = CastingPermission.Prototype(ManaCost.parse("{3}{G}"), power = 3, toughness = 3)
            val json = ProtocolJson.encodeToString<CastingPermissionDto>(prototype.toDto())
            json shouldBe """{"type":"prototype","cost":"{3}{G}","power":3,"toughness":3}"""
            ProtocolJson.decodeFromString<CastingPermissionDto>(json).toDomain() shouldBe prototype
        }

        "CR 702.85a: the cascade permission carries a payload-free discriminator and round-trips" {
            val json = ProtocolJson.encodeToString<CastingPermissionDto>(CastingPermission.Cascade.toDto())
            json shouldContain "\"type\":\"cascade\""
            ProtocolJson.decodeFromString<CastingPermissionDto>(json).toDomain() shouldBe CastingPermission.Cascade
        }

        "CR 718.3b: the prototype marker rides on every game object and round-trips" {
            val golem =
                GameObject(ObjectId(4), CardRef("Boulderbranch Golem"), PlayerId(0)).copy(prototyped = true)
            golem.toDto().prototyped shouldBe true
            golem.toDto().toDomain() shouldBe golem
            // Absent on an ordinary permanent, so an ordinary board's payload does not move.
            GameObject(ObjectId(5), CardRef("Grizzly Bears"), PlayerId(0)).toDto().prototyped shouldBe false
        }

        "CR 702.85a: a cascade awaiting its yes/no round-trips, candidate and exiled cards alike" {
            val pending =
                PendingCascade(
                    controller = PlayerId(0),
                    exiledObjectIds = persistentListOf(ObjectId(10), ObjectId(11), ObjectId(12)),
                    candidateObjectId = ObjectId(12),
                )
            val json = ProtocolJson.encodeToString(pending.toDto())
            json shouldBe """{"controller":0,"exiledObjectIds":[10,11,12],"candidateObjectId":12}"""
            ProtocolJson.decodeFromString<PendingCascadeDto>(json).toDomain() shouldBe pending
        }

        "CR 702.85a: a cascade whose free cast is in progress carries a null candidate, not a missing record" {
            // The record outlives its own decision: the candidate is cleared when the controller says
            // yes, and the exiled ids stay so the bottoming still knows what to put back.
            val casting =
                PendingCascade(
                    controller = PlayerId(1),
                    exiledObjectIds = persistentListOf(ObjectId(20), ObjectId(21)),
                    candidateObjectId = null,
                )
            ProtocolJson
                .decodeFromString<PendingCascadeDto>(
                    ProtocolJson.encodeToString(casting.toDto()),
                ).toDomain() shouldBe casting
        }
    })
