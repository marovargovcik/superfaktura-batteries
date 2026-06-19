package superfaktura

import io.circe.Decoder
import io.circe.syntax.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class PlanCodecTest extends AnyFreeSpec with Matchers:

  private val plan = Plan(
    List(
      PlanItem(
        PlanAction.CreateExpense(
          ref = ExternalRef("abc123"),
          expense = CandidateExpense(ExternalRef("abc123"), "SHELL 8203", Money(BigDecimal("73.71"), "EUR"),
            LocalDate.of(2026, 6, 16)),
          attach = Some(ReceiptRef("/receipts/shell.pdf"))
        ),
        PlanItemStatus.Pending
      ),
      PlanItem(PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/receipts/inv.pdf")), PlanItemStatus.Applied),
      PlanItem(PlanAction.SkipDuplicate(ExternalRef("def456"), "already booked", ExpenseId(7)), PlanItemStatus.Skipped),
      PlanItem(PlanAction.NeedsResolution(ExternalRef("ghi789"), List(ExpenseId(1), ExpenseId(2)), "ambiguous amount"),
        PlanItemStatus.Pending)
    )
  )

  "Plan codec" - {
    "round-trips every PlanItem variant through JSON" in {
      Decoder[Plan].decodeJson(plan.asJson) shouldBe Right(plan)
    }
  }
