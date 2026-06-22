package superfaktura.rule

import java.time.LocalDate

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RulesTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)

  private val ruleSet = RuleSet(
    List(
      Rule(RuleMatch.ExactName("SHELL 8203"), Some("Fuel — Shell"), None),
      Rule(RuleMatch.PartialName("AWS"), Some("AWS hosting {date}"), None),
      Rule(RuleMatch.ExactRecipient("SK1234567890"), None, Some("/invoices/rent.pdf"))
    )
  )

  "firstMatch" - {
    "matches on exact name, substring, and recipient IBAN" in {
      Rules.firstMatch(ruleSet, "SHELL 8203", None) shouldBe Some(ruleSet.rules.head)
      Rules.firstMatch(ruleSet, "Monthly AWS bill", None) shouldBe Some(ruleSet.rules(1))
      Rules.firstMatch(ruleSet, "anything", Some("SK1234567890")) shouldBe Some(ruleSet.rules(2))
    }

    "returns the first matching rule when several match, in file order" in {
      val overlapping = RuleSet(
        List(
          Rule(RuleMatch.PartialName("SHELL"), Some("first"), None),
          Rule(RuleMatch.ExactName("SHELL 8203"), Some("second"), None)
        )
      )
      Rules.firstMatch(overlapping, "SHELL 8203", None).flatMap(_.rename) shouldBe Some("first")
    }

    "returns None when nothing matches" in {
      Rules.firstMatch(ruleSet, "TESCO", Some("SK9999999999")) shouldBe None
    }

    "matches PartialName case-sensitively and never matches ExactRecipient when the IBAN is absent" in {
      Rules.firstMatch(ruleSet, "monthly aws bill", None) shouldBe None
      Rules.firstMatch(ruleSet, "anything", None) shouldBe None
    }
  }

  "renderName" - {
    "substitutes every {date} with the dd.MM.yyyy transaction date" in {
      Rules.renderName("Hosting {date} (paid {date})", date) shouldBe "Hosting 16.06.2026 (paid 16.06.2026)"
    }

    "leaves a template without {date} unchanged" in {
      Rules.renderName("Fuel — Shell", date) shouldBe "Fuel — Shell"
    }
  }
end RulesTest
