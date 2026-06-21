package superfaktura.matching

import java.time.LocalDate

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.Money
import superfaktura.bank.{CandidateExpense, ExternalRef}
import superfaktura.expense.{Expense, ExpenseId}
import superfaktura.receipt.{Receipt, ReceiptRef}

class ReceiptMatcherTest extends AnyFreeSpec with Matchers:

  private val window = MatchWindow.default
  private val day = LocalDate.of(2026, 6, 10)

  private def receipt(ref: String, amount: String, date: LocalDate = day, currency: String = "EUR"): Receipt =
    Receipt(ReceiptRef(ref), Money(BigDecimal(amount), currency), date)

  private def candidate(ref: String, amount: String, on: LocalDate = day): MatchTarget =
    MatchTarget.Candidate(CandidateExpense(ExternalRef(ref), "n", Money(BigDecimal(amount), "EUR"), on))

  private def existing(id: Long, amount: String, on: LocalDate): MatchTarget =
    MatchTarget.Existing(Expense(ExpenseId(id), "n", Money(BigDecimal(amount), "EUR"), on, None))

  "matchReceipts" - {
    "pairs a receipt to the single equal-amount new candidate inside the date window" in {
      val r = receipt("r.pdf", "73.71", LocalDate.of(2026, 6, 13))
      val c = candidate("c1", "73.71", LocalDate.of(2026, 6, 15))

      val result = ReceiptMatcher.matchReceipts(List(r), List(c), window)
      result.paired shouldBe List(Pairing(r, c))
      result.ambiguousReceipts shouldBe empty
      result.contestedTargets shouldBe empty
      result.unmatchedReceipts shouldBe empty
      result.unmatchedTargets shouldBe empty
    }

    "pairs a receipt to an existing Superfaktura expense (attach-to-existing)" in {
      val r = receipt("r.pdf", "20.00", LocalDate.of(2026, 6, 13))
      val e = existing(id = 7, amount = "20.00", on = LocalDate.of(2026, 6, 14))

      ReceiptMatcher.matchReceipts(List(r), List(e), window).paired shouldBe List(Pairing(r, e))
    }

    "treats amounts as equal regardless of decimal scale" in {
      val r = receipt("r.pdf", "73.7")
      val c = candidate("c1", "73.70")

      ReceiptMatcher.matchReceipts(List(r), List(c), window).paired.map(_.target) shouldBe List(c)
    }

    "requires the currency to match, not just the number" in {
      val r = receipt("r.pdf", "50.00", currency = "USD")
      val c = candidate("c1", "50.00")

      val result = ReceiptMatcher.matchReceipts(List(r), List(c), window)
      result.paired shouldBe empty
      result.unmatchedReceipts shouldBe List(r)
      result.unmatchedTargets shouldBe List(c)
    }

    "includes the −1 and +3 window bounds and excludes dates beyond them" in {
      def pairsAtOffset(offset: Int): Boolean =
        val r = receipt("r.pdf", "5.00")
        val c = candidate("c", "5.00", day.plusDays(offset))
        ReceiptMatcher.matchReceipts(List(r), List(c), window).paired.nonEmpty

      pairsAtOffset(-1) shouldBe true
      pairsAtOffset(3) shouldBe true
      pairsAtOffset(-2) shouldBe false
      pairsAtOffset(4) shouldBe false
    }

    "surfaces a receipt matching several targets as ambiguous, never silently paired" in {
      val r = receipt("r.pdf", "5.00")
      val c1 = candidate("c1", "5.00", day)
      val c2 = candidate("c2", "5.00", day.plusDays(1))

      val result = ReceiptMatcher.matchReceipts(List(r), List(c1, c2), window)
      result.paired shouldBe empty
      result.ambiguousReceipts shouldBe List(AmbiguousReceipt(r, List(c1, c2)))
    }

    "surfaces several receipts contending for one target as a contested target, not unmatched" in {
      val r1 = receipt("r1.pdf", "5.00", day)
      val r2 = receipt("r2.pdf", "5.00", day.plusDays(1))
      val c = candidate("c", "5.00", day.plusDays(1))

      val result = ReceiptMatcher.matchReceipts(List(r1, r2), List(c), window)
      result.paired shouldBe empty
      result.contestedTargets shouldBe List(ContestedTarget(c, List(r1, r2)))
      result.unmatchedTargets shouldBe empty
    }

    "partitions a mixed run into paired, ambiguous, contested, and unmatched buckets" in {
      val rPair = receipt("pair.pdf", "1.00")
      val cPair = candidate("c-pair", "1.00")
      val rAmbiguous = receipt("amb.pdf", "2.00")
      val cA1 = candidate("c-a1", "2.00", day)
      val cA2 = candidate("c-a2", "2.00", day.plusDays(1))
      val rC1 = receipt("c1.pdf", "3.00")
      val rC2 = receipt("c2.pdf", "3.00")
      val cContested = candidate("c-contested", "3.00")
      val rUnmatched = receipt("orphan.pdf", "9.99")
      val cUnmatched = candidate("c-orphan", "8.88")

      val result = ReceiptMatcher.matchReceipts(
        List(rPair, rAmbiguous, rC1, rC2, rUnmatched),
        List(cPair, cA1, cA2, cContested, cUnmatched),
        window
      )
      result.paired shouldBe List(Pairing(rPair, cPair))
      result.ambiguousReceipts shouldBe List(AmbiguousReceipt(rAmbiguous, List(cA1, cA2)))
      result.contestedTargets shouldBe List(ContestedTarget(cContested, List(rC1, rC2)))
      result.unmatchedReceipts shouldBe List(rUnmatched)
      result.unmatchedTargets shouldBe List(cUnmatched)
    }

    "with no receipts leaves every target unmatched, and vice versa" in {
      val c = candidate("c", "5.00")
      val r = receipt("r.pdf", "5.00")

      ReceiptMatcher.matchReceipts(Nil, List(c), window).unmatchedTargets shouldBe List(c)
      ReceiptMatcher.matchReceipts(List(r), Nil, window).unmatchedReceipts shouldBe List(r)
    }
  }
end ReceiptMatcherTest
