package dev.mtgplay.core.zone

import dev.mtgplay.core.identity.PlayerId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Zone identity per CR 4: the per-player zones carry their owner; the shared zones are
 * singletons.
 */
class ZoneIdSpec :
    StringSpec({
        val alice = PlayerId(0)
        val bob = PlayerId(1)

        "CR 400.2: library, hand, and graveyard are per-player — different owners, different zones" {
            ZoneId.Library(alice) shouldNotBe ZoneId.Library(bob)
            ZoneId.Hand(alice) shouldNotBe ZoneId.Hand(bob)
            ZoneId.Graveyard(alice) shouldNotBe ZoneId.Graveyard(bob)
        }

        "per-player zone identity is by value: the same owner names the same zone" {
            ZoneId.Library(alice) shouldBe ZoneId.Library(PlayerId(0))
        }

        "CR 400.2: battlefield, stack, and exile are shared — one zone regardless of player" {
            val zones: Set<ZoneId> = setOf(ZoneId.Battlefield, ZoneId.Stack, ZoneId.Exile)
            zones.size shouldBe 3
        }

        "CR 4: the six modeled zone identities are pairwise distinct" {
            val zones: Set<ZoneId> =
                setOf(
                    ZoneId.Library(alice),
                    ZoneId.Hand(alice),
                    ZoneId.Graveyard(alice),
                    ZoneId.Battlefield,
                    ZoneId.Stack,
                    ZoneId.Exile,
                )
            zones.size shouldBe 6
        }
    })
