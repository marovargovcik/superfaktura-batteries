package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ReceiptMatcherTest extends AnyFreeSpec with Matchers:

  private val window = MatchWindow.default

  private def receipt(ref: String, amount: String, date: LocalDate): Receipt =
    Receipt(ReceiptRef(ref), Money(BigDecimal(amount), "EUR"), date)

  private def candidate(ref: String, amount: String, on: LocalDate): CandidateExpense =
    CandidateExpense(ExternalRef(ref), "n", Money(BigDecimal(amount), "EUR"), on)

  "matchReceipts" - {
    "pairs a receipt to the single equal-amount transaction inside the date window" in {
      val r = receipt("r.pdf", "73.71", LocalDate.of(2026, 6, 13))
      val c = candidate("c1", "73.71", LocalDate.of(2026, 6, 15))

      val result = ReceiptMatcher.matchReceipts(List(r), List(c), window)
      result.paired shouldBe List(Pairing(r, c))
      result.ambiguous shouldBe empty
      result.unmatchedReceipts shouldBe empty
      result.unmatchedCandidates shouldBe empty
    }

    "treats amounts as equal regardless of decimal scale" in {
      val r = receipt("r.pdf", "73.7", LocalDate.of(2026, 6, 13))
      val c = candidate("c1", "73.70", LocalDate.of(2026, 6, 13))

      ReceiptMatcher.matchReceipts(List(r), List(c), window).paired.map(_.candidate) shouldBe List(c)
    }

    "includes the −1 and +3 window bounds and excludes dates beyond them" in {
      def pairsAtOffset(offset: Int): Boolean =
        val r = receipt("r.pdf", "5.00", LocalDate.of(2026, 6, 10))
        val c = candidate("c", "5.00", LocalDate.of(2026, 6, 10).plusDays(offset))
        ReceiptMatcher.matchReceipts(List(r), List(c), window).paired.nonEmpty

      pairsAtOffset(-1) shouldBe true
      pairsAtOffset(3) shouldBe true
      pairsAtOffset(-2) shouldBe false
      pairsAtOffset(4) shouldBe false
    }

    "surfaces a receipt matching several transactions as ambiguous, never silently paired" in {
      val r = receipt("r.pdf", "5.00", LocalDate.of(2026, 6, 10))
      val c1 = candidate("c1", "5.00", LocalDate.of(2026, 6, 10))
      val c2 = candidate("c2", "5.00", LocalDate.of(2026, 6, 11))

      val result = ReceiptMatcher.matchReceipts(List(r), List(c1, c2), window)
      result.paired shouldBe empty
      result.ambiguous shouldBe List(AmbiguousReceipt(r, List(c1, c2)))
      result.unmatchedCandidates shouldBe empty
    }

    "surfaces several receipts matching one transaction as ambiguous" in {
      val r1 = receipt("r1.pdf", "5.00", LocalDate.of(2026, 6, 10))
      val r2 = receipt("r2.pdf", "5.00", LocalDate.of(2026, 6, 11))
      val c = candidate("c", "5.00", LocalDate.of(2026, 6, 11))

      val result = ReceiptMatcher.matchReceipts(List(r1, r2), List(c), window)
      result.paired shouldBe empty
      result.ambiguous.map(_.receipt) shouldBe List(r1, r2)
      result.unmatchedCandidates shouldBe empty
    }

    "lists unmatched receipts and transactions when amount or date rules out a match" in {
      val r = receipt("r.pdf", "5.00", LocalDate.of(2026, 6, 10))
      val wrongAmount = candidate("c1", "9.99", LocalDate.of(2026, 6, 10))
      val outOfWindow = candidate("c2", "5.00", LocalDate.of(2026, 6, 20))

      val result = ReceiptMatcher.matchReceipts(List(r), List(wrongAmount, outOfWindow), window)
      result.paired shouldBe empty
      result.ambiguous shouldBe empty
      result.unmatchedReceipts shouldBe List(r)
      result.unmatchedCandidates shouldBe List(wrongAmount, outOfWindow)
    }
  }
end ReceiptMatcherTest
