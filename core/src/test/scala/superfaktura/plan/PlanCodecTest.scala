package superfaktura.plan

import java.time.LocalDate

import io.circe.{Decoder, Json}
import io.circe.syntax.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.Money
import superfaktura.bank.{CandidateExpense, ExternalRef}
import superfaktura.expense.ExpenseId
import superfaktura.receipt.ReceiptRef

class PlanCodecTest extends AnyFreeSpec with Matchers:

  private val plan = Plan(
    List(
      PlanItem(
        PlanAction.CreateExpense(
          ref = ExternalRef("abc123"),
          expense = CandidateExpense(
            ExternalRef("abc123"),
            "SHELL 8203",
            Money(BigDecimal("73.71"), "EUR"),
            LocalDate.of(2026, 6, 16)
          ),
          attach = Some(ReceiptRef("/receipts/shell.pdf"))
        ),
        PlanItemStatus.Pending
      ),
      PlanItem(
        PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/receipts/inv.pdf"), Some("sfref:abc")),
        PlanItemStatus.Applied
      ),
      PlanItem(PlanAction.SkipDuplicate(ExternalRef("def456"), "already booked", ExpenseId(7)), PlanItemStatus.Skipped),
      PlanItem(PlanAction.RenameExpense(ExpenseId(8), "Rent 16.06.2026"), PlanItemStatus.Pending),
      PlanItem(
        PlanAction.ReceiptAlreadyUploaded(ReceiptRef("/receipts/orange.jpg"), ExpenseId(20)),
        PlanItemStatus.Skipped
      )
    )
  )

  "Plan codec" - {
    "round-trips every PlanItem variant through JSON" in {
      Decoder[Plan].decodeJson(plan.asJson) shouldBe Right(plan)
    }

    "encodes a PlanAction with a flat `type` discriminator" in {
      val action: PlanAction = PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/x.pdf"), None)
      action.asJson.hcursor.get[String]("type") shouldBe Right("AttachToExisting")
    }

    "rejects an unknown status string" in {
      Decoder[PlanItemStatus].decodeJson(Json.fromString("Bogus")).isLeft shouldBe true
    }
  }
end PlanCodecTest
