package superfaktura.cli.plan

import java.nio.file.Files
import java.time.LocalDate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.{CliError, Money}
import superfaktura.bank.{CandidateExpense, ExternalRef}
import superfaktura.expense.ExpenseId
import superfaktura.plan.{Plan, PlanAction, PlanItem, PlanItemStatus}
import superfaktura.receipt.ReceiptRef

class FilePlanStoreTest extends AnyFreeSpec with Matchers:

  private val plan = Plan(
    List(
      PlanItem(
        PlanAction.CreateExpense(
          ExternalRef("abc"),
          CandidateExpense(
            ExternalRef("abc"),
            "SHELL 8203",
            Money(BigDecimal("73.71"), "EUR"),
            LocalDate.of(2026, 6, 16)
          ),
          Some(ReceiptRef("/receipts/shell.pdf"))
        ),
        PlanItemStatus.Pending
      ),
      PlanItem(
        PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/receipts/inv.pdf"), Some("sfref:abc")),
        PlanItemStatus.Applied
      )
    )
  )

  "FilePlanStore" - {
    "round-trips a plan through a JSON file" in {
      val tmp = Files.createTempFile("plan", ".json")
      val store = FilePlanStore.at[IO](tmp)
      val test =
        for
          _ <- store.save(plan)
          loaded <- store.load
        yield loaded shouldBe plan
      test.unsafeRunSync()
    }

    "fails with PlanInvalid on malformed JSON" in {
      val tmp = Files.createTempFile("plan-bad", ".json")
      Files.write(tmp, "{ not valid json".getBytes)

      val test =
        for
          result <- FilePlanStore.at[IO](tmp).load.attempt
        yield result match
          case Left(_: CliError.PlanInvalid) => succeed
          case other => fail(s"expected PlanInvalid, got: $other")
      test.unsafeRunSync()
    }
  }
end FilePlanStoreTest
