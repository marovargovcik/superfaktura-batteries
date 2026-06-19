package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ExpensePlannerTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 19)

  private def tx(
      direction: TransactionType,
      amount: String,
      recipientInfo: Option[String],
      description: String,
      variableSymbol: Option[String] = None,
      iban: Option[String] = None
  ): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = direction,
      counterpartyIban = iban,
      variableSymbol = variableSymbol,
      specificSymbol = None,
      constantSymbol = None,
      recipientInfo = recipientInfo,
      description = description
    )

  "toCandidates" - {
    "drops credits and derives the name as card merchant, else recipient info, else description" in {
      val card = tx(
        TransactionType.Debit,
        "73.71",
        Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        "GP NÁKUP POS"
      )
      val transfer = tx(TransactionType.Debit, "45.45", Some("UHRADA POISTNEHO"), "Platba 8180/000000-7000747747")
      val fee = tx(TransactionType.Debit, "3.04", None, "Transakčná daň 12.06.2026")
      val credit = tx(TransactionType.Credit, "7850.00", None, "INV 2026005")

      ExpensePlanner.toCandidates(List(card, credit, transfer, fee)).map(_.name) shouldBe List(
        "SHELL 8203",
        "UHRADA POISTNEHO",
        "Transakčná daň 12.06.2026"
      )
    }

    "derives a deterministic external ref from the identifying fields" in {
      val transfer = tx(
        TransactionType.Debit,
        "45.45",
        Some("UHRADA POISTNEHO"),
        "Platba 8180",
        variableSymbol = Some("5646196800"),
        iban = Some("SK5281800000007000747747")
      )

      ExpensePlanner.toCandidates(List(transfer)).head.externalRef shouldBe
        ExternalRef("2026-06-19|45.45|EUR|5646196800||SK5281800000007000747747|Platba 8180")
    }
  }
end ExpensePlannerTest
