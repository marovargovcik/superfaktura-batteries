package superfaktura

import superfaktura.bank.{CandidateExpense, ExternalRef}
import superfaktura.expense.{ExpenseId, ExpensePatch, NewExpense, SuperfakturaAlgebra, SuperfakturaAlgebraStub}
import superfaktura.plan.{ExpensePlanner, Plan, PlanAction, PlanItem, PlanItemStatus, PlanStore, PlanStoreStub}
import superfaktura.receipt.{
  AttachmentFormat,
  ImagePrepAlgebra,
  ImagePrepAlgebraStub,
  PreparedAttachment,
  ReceiptBytes,
  ReceiptRef,
  ReceiptSourceAlgebra,
  ReceiptSourceAlgebraStub
}

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector

import java.time.LocalDate

class ApplyProgramTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)
  private val candidate = CandidateExpense(ExternalRef("r1"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)
  private val rawReceipt = ReceiptBytes(ByteVector(1, 2, 3))
  private val prepared = ReceiptBytes(ByteVector(9, 9))
  // The marker hashes the original bytes, not the fitted ones, so a later run recognises the file on disk.
  private val marker = ExpensePlanner.receiptMarker(rawReceipt)

  private def planStore(plan: Plan, saved: Ref[IO, Option[Plan]]): PlanStore[IO] = new PlanStoreStub[IO]:
    override def load: IO[Plan] = IO.pure(plan)
    override def save(updated: Plan): IO[Unit] = saved.set(Some(updated))

  private def loadsReceipt: ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO]:
    override def load(ref: ReceiptRef): IO[ReceiptBytes] = IO.pure(rawReceipt)

  private def prep(result: PreparedAttachment): ImagePrepAlgebra[IO] = new ImagePrepAlgebraStub[IO]:
    override def fit(attachment: ReceiptBytes, format: AttachmentFormat): IO[PreparedAttachment] = IO.pure(result)

  private given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
    override def summary(plan: Plan): IO[Unit] = IO.unit

  private def recordingSuperfaktura(
      added: Ref[IO, List[(NewExpense, Option[ReceiptBytes])]],
      edited: Ref[IO, List[(ExpenseId, ExpensePatch)]]
  ): SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
    override def addExpense(request: NewExpense, attachment: Option[ReceiptBytes]): IO[ExpenseId] =
      added.update(_ :+ (request, attachment)).as(ExpenseId(100))
    override def editExpense(id: ExpenseId, patch: ExpensePatch): IO[Unit] = edited.update(_ :+ (id, patch))

  "ApplyProgram.run" - {
    "creates a pending expense with its prepared attachment and leaves duplicates untouched" in {
      val saved = Ref.unsafe[IO, Option[Plan]](None)
      val added = Ref.unsafe[IO, List[(NewExpense, Option[ReceiptBytes])]](Nil)
      val edited = Ref.unsafe[IO, List[(ExpenseId, ExpensePatch)]](Nil)
      val plan = Plan(
        List(
          PlanItem(
            PlanAction.CreateExpense(candidate.externalRef, candidate, Some(ReceiptRef("r.jpg"))),
            PlanItemStatus.Pending
          ),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(9)), PlanItemStatus.Skipped)
        )
      )
      given PlanStore[IO] = planStore(plan, saved)
      given SuperfakturaAlgebra[IO] = recordingSuperfaktura(added, edited)
      given ReceiptSourceAlgebra[IO] = loadsReceipt
      given ImagePrepAlgebra[IO] = prep(PreparedAttachment.Fitted(prepared))

      ApplyProgram.run[IO].unsafeRunSync()

      val expected =
        ExpensePlanner.newExpense(candidate.externalRef, candidate).copy(comment = Some(s"sfref:r1 ${marker.value}"))
      added.get.unsafeRunSync() shouldBe List((expected, Some(prepared)))
      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.map(_.status) shouldBe
        List(PlanItemStatus.Applied, PlanItemStatus.Skipped)
    }

    "still books the expense without the attachment when ImagePrep flags it too large" in {
      val saved = Ref.unsafe[IO, Option[Plan]](None)
      val added = Ref.unsafe[IO, List[(NewExpense, Option[ReceiptBytes])]](Nil)
      val edited = Ref.unsafe[IO, List[(ExpenseId, ExpensePatch)]](Nil)
      val plan = Plan(
        List(
          PlanItem(
            PlanAction.CreateExpense(candidate.externalRef, candidate, Some(ReceiptRef("big.pdf"))),
            PlanItemStatus.Pending
          )
        )
      )
      given PlanStore[IO] = planStore(plan, saved)
      given SuperfakturaAlgebra[IO] = recordingSuperfaktura(added, edited)
      given ReceiptSourceAlgebra[IO] = loadsReceipt
      given ImagePrepAlgebra[IO] = prep(PreparedAttachment.TooLarge("over the cap"))

      ApplyProgram.run[IO].unsafeRunSync()

      added.get.unsafeRunSync() shouldBe List((ExpensePlanner.newExpense(candidate.externalRef, candidate), None))
      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.map(_.status) shouldBe
        List(PlanItemStatus.Applied)
    }

    "attaches to an existing expense" in {
      val saved = Ref.unsafe[IO, Option[Plan]](None)
      val added = Ref.unsafe[IO, List[(NewExpense, Option[ReceiptBytes])]](Nil)
      val edited = Ref.unsafe[IO, List[(ExpenseId, ExpensePatch)]](Nil)
      val plan = Plan(
        List(
          PlanItem(
            PlanAction.AttachToExisting(ExpenseId(7), ReceiptRef("r.png"), Some("sfref:e7")),
            PlanItemStatus.Pending
          )
        )
      )
      given PlanStore[IO] = planStore(plan, saved)
      given SuperfakturaAlgebra[IO] = recordingSuperfaktura(added, edited)
      given ReceiptSourceAlgebra[IO] = loadsReceipt
      given ImagePrepAlgebra[IO] = prep(PreparedAttachment.Fitted(prepared))

      ApplyProgram.run[IO].unsafeRunSync()

      edited.get.unsafeRunSync() shouldBe
        List((ExpenseId(7), ExpensePatch(Some(prepared), Some(s"sfref:e7 ${marker.value}"))))
      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.map(_.status) shouldBe
        List(PlanItemStatus.Applied)
    }

    "fails an attach-to-existing when the receipt cannot be made to fit" in {
      val saved = Ref.unsafe[IO, Option[Plan]](None)
      val added = Ref.unsafe[IO, List[(NewExpense, Option[ReceiptBytes])]](Nil)
      val edited = Ref.unsafe[IO, List[(ExpenseId, ExpensePatch)]](Nil)
      val plan =
        Plan(List(PlanItem(
          PlanAction.AttachToExisting(ExpenseId(7), ReceiptRef("big.pdf"), None),
          PlanItemStatus.Pending
        )))
      given PlanStore[IO] = planStore(plan, saved)
      given SuperfakturaAlgebra[IO] = recordingSuperfaktura(added, edited)
      given ReceiptSourceAlgebra[IO] = loadsReceipt
      given ImagePrepAlgebra[IO] = prep(PreparedAttachment.TooLarge("over the cap"))

      ApplyProgram.run[IO].unsafeRunSync()

      edited.get.unsafeRunSync() shouldBe empty
      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.map(_.status) shouldBe
        List(PlanItemStatus.Failed)
    }

    "is idempotent: an already-applied create is not re-created and never touches the receipt source" in {
      val saved = Ref.unsafe[IO, Option[Plan]](None)
      val added = Ref.unsafe[IO, List[(NewExpense, Option[ReceiptBytes])]](Nil)
      val edited = Ref.unsafe[IO, List[(ExpenseId, ExpensePatch)]](Nil)
      val plan = Plan(
        List(
          PlanItem(
            PlanAction.CreateExpense(candidate.externalRef, candidate, Some(ReceiptRef("r.jpg"))),
            PlanItemStatus.Applied
          )
        )
      )
      given PlanStore[IO] = planStore(plan, saved)
      given SuperfakturaAlgebra[IO] = recordingSuperfaktura(added, edited)
      // load/fit left unimplemented (???): the test fails if an Applied item touches them again.
      given ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO] {}
      given ImagePrepAlgebra[IO] = new ImagePrepAlgebraStub[IO] {}

      ApplyProgram.run[IO].unsafeRunSync()

      added.get.unsafeRunSync() shouldBe empty
    }
  }
end ApplyProgramTest
