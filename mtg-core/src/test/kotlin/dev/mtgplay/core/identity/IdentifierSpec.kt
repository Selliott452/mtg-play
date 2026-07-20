package dev.mtgplay.core.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Construction validation of the identifier value classes. The CR 400.7 allocation semantics
 * of [ObjectId] are covered by `GameStateSpec`.
 */
class IdentifierSpec :
    StringSpec({
        "PlayerId accepts any non-negative seat; two-player is not hardcoded into the type" {
            PlayerId(0).seat shouldBe 0
            PlayerId(5).seat shouldBe 5
        }

        "PlayerId rejects a negative seat" {
            shouldThrow<IllegalArgumentException> { PlayerId(-1) }
        }

        "ObjectId rejects a negative id" {
            shouldThrow<IllegalArgumentException> { ObjectId(-1) }
        }

        "CardRef carries the printed card name" {
            CardRef("Lightning Bolt").name shouldBe "Lightning Bolt"
        }

        "CardRef rejects a blank name" {
            shouldThrow<IllegalArgumentException> { CardRef(" ") }
        }
    })
