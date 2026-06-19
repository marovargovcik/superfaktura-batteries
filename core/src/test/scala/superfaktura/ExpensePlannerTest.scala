package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ExpensePlannerTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 19)

  private def debit(amount: String, variableSymbol: Option[String], description: String): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), currency = "EUR"),
      direction = TransactionType.Debit,
      variableSymbol = variableSymbol,
      description = description
    )

  "toCandidates" - {
    "keeps debits in input order, drops credits, and maps each to a full candidate" in {
      val orange = debit("45.45", Some("123"), "ORANGE")
      val credit = Transaction(
        date = date,
        amount = Money(BigDecimal("100.00"), currency = "EUR"),
        direction = TransactionType.Credit,
        variableSymbol = None,
        description = "INV 2026005"
      )
      val shell = debit("73.71", None, "SHELL 8203")

      ExpensePlanner.toCandidates(List(orange, credit, shell)) shouldBe List(
        CandidateExpense(
          ExternalRef("2026-06-19|45.45|EUR|123|ORANGE"),
          "ORANGE",
          Money(BigDecimal("45.45"), "EUR"),
          date
        ),
        CandidateExpense(
          ExternalRef("2026-06-19|73.71|EUR||SHELL 8203"),
          "SHELL 8203",
          Money(BigDecimal("73.71"), "EUR"),
          date
        )
      )
    }
  }
end ExpensePlannerTest
