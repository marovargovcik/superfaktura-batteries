package superfaktura

import superfaktura.domain.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ExpensePlannerTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 19)

  "toCandidates" - {
    "keeps only debits and maps them to candidates" in {
      val debit = Transaction(date, Money(BigDecimal("45.45"), "EUR"), TransactionType.Debit, Some("123"), "ORANGE")
      val credit = Transaction(date, Money(BigDecimal("100.00"), "EUR"), TransactionType.Credit, None, "INV 2026005")

      val result = ExpensePlanner.toCandidates(List(debit, credit))

      result.map(_.name) shouldBe List("ORANGE")
      result.head.amount shouldBe Money(BigDecimal("45.45"), "EUR")
    }

    "derives a stable external ref from the transaction fields" in {
      val debit = Transaction(date, Money(BigDecimal("45.45"), "EUR"), TransactionType.Debit, Some("123"), "ORANGE")

      val first = ExpensePlanner.toCandidates(List(debit)).head.externalRef
      val second = ExpensePlanner.toCandidates(List(debit)).head.externalRef

      first shouldBe second
      first shouldBe ExternalRef("2026-06-19|45.45|EUR|123|ORANGE")
    }
  }
