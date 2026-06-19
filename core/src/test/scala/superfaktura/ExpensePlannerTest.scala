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
      specificSymbol: Option[String] = None,
      iban: Option[String] = None
  ): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = direction,
      counterpartyIban = iban,
      variableSymbol = variableSymbol,
      specificSymbol = specificSymbol,
      recipientInfo = recipientInfo,
      description = description
    )

  private def refOf(transaction: Transaction): ExternalRef =
    ExpensePlanner.toCandidates(List(transaction)).head.externalRef

  "toCandidates" - {
    "drops credits and derives the name as card merchant, else recipient info, else description" in {
      val card = tx(
        direction = TransactionType.Debit,
        amount = "73.71",
        recipientInfo = Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        description = "GP NÁKUP POS"
      )
      val transfer = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = Some("UHRADA POISTNEHO"),
        description = "Platba 8180/000000-7000747747"
      )
      val fee = tx(
        direction = TransactionType.Debit,
        amount = "3.04",
        recipientInfo = None,
        description = "Transakčná daň 12.06.2026"
      )
      val credit = tx(
        direction = TransactionType.Credit,
        amount = "7850.00",
        recipientInfo = None,
        description = "INV 2026005"
      )

      ExpensePlanner.toCandidates(List(card, credit, transfer, fee)).map(_.name) shouldBe List(
        "SHELL 8203",
        "UHRADA POISTNEHO",
        "Transakčná daň 12.06.2026"
      )
    }

    "does not extract a merchant from a non-card row whose recipient info is amount-shaped" in {
      val transfer = tx(
        direction = TransactionType.Debit,
        amount = "12.34",
        recipientInfo = Some("12.34EUR FOO"),
        description = "Platba 123"
      )

      ExpensePlanner.toCandidates(List(transfer)).map(_.name) shouldBe List("12.34EUR FOO")
    }

    "derives a deterministic external ref, distinct even where a naive join would collide" in {
      val base = tx(direction = TransactionType.Debit, amount = "45.45", recipientInfo = None, description = "x")
      refOf(base) shouldBe refOf(base)

      val a = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = None,
        description = "x",
        variableSymbol = Some("1"),
        specificSymbol = Some("2|3")
      )
      val b = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = None,
        description = "x",
        variableSymbol = Some("1|2"),
        specificSymbol = Some("3")
      )

      refOf(a) should not be refOf(b)
    }
  }
end ExpensePlannerTest
