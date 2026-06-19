package superfaktura.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.*

import java.nio.file.Files
import java.time.LocalDate

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
      PlanItem(PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/receipts/inv.pdf")), PlanItemStatus.Applied)
    )
  )

  "FilePlanStore" - {
    "round-trips a plan through a JSON file" in {
      val tmp = Files.createTempFile("plan", ".json")
      val store = FilePlanStore.at[IO](tmp)
      val loaded = store.save(plan).flatMap(_ => store.load).unsafeRunSync()

      loaded shouldBe plan
    }

    "fails with PlanInvalid on malformed JSON" in {
      val tmp = Files.createTempFile("plan-bad", ".json")
      Files.write(tmp, "{ not valid json".getBytes)

      FilePlanStore.at[IO](tmp).load.attempt.unsafeRunSync() match
        case Left(_: CliError.PlanInvalid) => succeed
        case other => fail(s"expected PlanInvalid, got: $other")
    }
  }
end FilePlanStoreTest
