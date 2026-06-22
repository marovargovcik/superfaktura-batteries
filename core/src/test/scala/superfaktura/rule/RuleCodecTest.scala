package superfaktura.rule

import io.circe.{Decoder, Json}
import io.circe.syntax.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RuleCodecTest extends AnyFreeSpec with Matchers:

  private val rules = RuleSet(
    List(
      Rule(RuleMatch.ExactName("SHELL 8203"), Some("Fuel — Shell"), None),
      Rule(RuleMatch.PartialName("AWS"), Some("AWS hosting {date}"), None),
      Rule(RuleMatch.ExactRecipient("SK1234567890"), Some("Landlord rent {date}"), Some("/invoices/rent.pdf"))
    )
  )

  "RuleSet codec" - {
    "round-trips every RuleMatch variant through JSON" in {
      Decoder[RuleSet].decodeJson(rules.asJson) shouldBe Right(rules)
    }

    "encodes a RuleMatch with a flat `type` discriminator" in {
      val matcher: RuleMatch = RuleMatch.ExactRecipient("SK1234567890")
      matcher.asJson.hcursor.get[String]("type") shouldBe Right("ExactRecipient")
    }

    "rejects an unknown match type" in {
      Decoder[RuleMatch].decodeJson(Json.obj("type" := "Bogus", "name" := "x")).isLeft shouldBe true
    }
  }
end RuleCodecTest
