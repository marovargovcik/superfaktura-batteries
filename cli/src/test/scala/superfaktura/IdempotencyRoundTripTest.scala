package superfaktura

import java.nio.file.{Path, Paths}
import java.time.LocalDate

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.bank.{BankStatementSourceAlgebra, BankStatementSourceAlgebraStub, Transaction, TransactionType}
import superfaktura.expense.{Expense, ExpenseId, ExpensePatch, NewExpense, SuperfakturaAlgebra, SuperfakturaAlgebraStub}
import superfaktura.plan.{ExpensePlanner, Plan, PlanAction, PlanStore, PlanStoreStub}
import superfaktura.receipt.{
  AttachmentFormat,
  ImagePrepAlgebra,
  ImagePrepAlgebraStub,
  OcrAlgebra,
  OcrAlgebraStub,
  OcrResult,
  PreparedAttachment,
  ReceiptBytes,
  ReceiptFile,
  ReceiptMedia,
  ReceiptRef,
  ReceiptSourceAlgebra,
  ReceiptSourceAlgebraStub
}
import superfaktura.rule.{Rule, RuleMatch, RuleSet, RuleStore}

// Drives the real plan -> apply sequence across stages through a stateful Superfaktura whose writes feed the next
// plan's listExpenses, proving the apply-output <-> plan-input contract converges regardless of prior-run state.
class IdempotencyRoundTripTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)
  private val csvPath = Paths.get("ignored.csv")
  private val receipts = Some(Paths.get("receipts"))

  private val landlord = Transaction(
    date = date,
    amount = Money(BigDecimal("450.00"), "EUR"),
    direction = TransactionType.Debit,
    counterpartyIban = None,
    variableSymbol = None,
    specificSymbol = None,
    recipientInfo = Some("LANDLORD"),
    description = "Platba"
  )

  private val receiptPath = ReceiptRef("receipts/r.jpg")
  private val ruleFile = ReceiptRef("/invoices/rent.pdf")
  private val receiptMarker = ExpensePlanner.receiptMarker(ReceiptBytes(ByteVector(1, 1, 1)))
  private val ruleFileMarker = ExpensePlanner.receiptMarker(ReceiptBytes(ByteVector(2, 2, 2)))

  "running plan/apply repeatedly across csv, then +receipts, then +rules converges" in {
    val store = Ref.unsafe[IO, List[Expense]](Nil)
    val nextId = Ref.unsafe[IO, Long](1L)
    val creates = Ref.unsafe[IO, Int](0)
    val edits = Ref.unsafe[IO, Int](0)
    val activeRules = Ref.unsafe[IO, RuleSet](RuleSet.empty)
    val savedPlan = Ref.unsafe[IO, Plan](Plan(Nil))

    given BankStatementSourceAlgebra[IO] = new BankStatementSourceAlgebraStub[IO]:
      override def read(path: Path): IO[List[Transaction]] = IO.pure(List(landlord))

    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
      override def listExpenses(window: DateWindow): IO[List[Expense]] = store.get
      override def addExpense(request: NewExpense, attachment: Option[ReceiptBytes]): IO[ExpenseId] =
        for
          _ <- creates.update(_ + 1)
          id <- nextId.getAndUpdate(_ + 1).map(ExpenseId(_))
          _ <- store.update(_ :+ Expense(id, request.name, request.amount, request.created, request.comment))
        yield id
      override def editExpense(id: ExpenseId, patch: ExpensePatch): IO[Unit] =
        edits.update(_ + 1) *> store.update(_.map { expense =>
          if expense.id == id then
            expense.copy(name = patch.name.getOrElse(expense.name), comment = patch.comment.orElse(expense.comment))
          else expense
        })

    given ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO]:
      override def list(folder: Path): IO[List[ReceiptFile]] = IO.pure(List(ReceiptFile(receiptPath, 100L)))
      override def exists(ref: ReceiptRef): IO[Boolean] = IO.pure(true)
      override def load(ref: ReceiptRef): IO[ReceiptBytes] = ref match
        case `receiptPath` => IO.pure(ReceiptBytes(ByteVector(1, 1, 1)))
        case `ruleFile` => IO.pure(ReceiptBytes(ByteVector(2, 2, 2)))
        case other => IO.raiseError(new IllegalStateException(s"unexpected load: ${other.path}"))

    given OcrAlgebra[IO] = new OcrAlgebraStub[IO]:
      override def read(receipt: ReceiptBytes, media: ReceiptMedia): IO[OcrResult] =
        IO.pure(OcrResult(Some(Money(BigDecimal("450.00"), "EUR")), Some(date)))

    given ImagePrepAlgebra[IO] = new ImagePrepAlgebraStub[IO]:
      override def fit(attachment: ReceiptBytes, format: AttachmentFormat): IO[PreparedAttachment] =
        IO.pure(PreparedAttachment.Fitted(attachment))

    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def save(plan: Plan): IO[Unit] = savedPlan.set(plan)
      override def load: IO[Plan] = savedPlan.get

    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    given RuleStore[IO] = new RuleStore[IO]:
      override def load: IO[RuleSet] = activeRules.get

    def cycle(withReceipts: Boolean): Unit =
      PlanProgram.run[IO](csvPath, Option.when(withReceipts)(receipts).flatten).unsafeRunSync()
      ApplyProgram.run[IO].unsafeRunSync()

    // Stage 1 — csv only: one expense is created, keeping its derived name.
    cycle(withReceipts = false)
    store.get.unsafeRunSync().map(e => e.name) shouldBe List("LANDLORD")

    // Stage 2 — csv + receipts: the transaction dedupes and its receipt attaches to the now-existing expense.
    cycle(withReceipts = true)
    store.get.unsafeRunSync().head.comment.exists(_.contains(receiptMarker.value)) shouldBe true

    // Stage 3 — csv + receipts + rules: rename the existing expense and attach the rule's fixed file.
    activeRules.set(RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), Some(ruleFile.path)))))
      .unsafeRunSync()
    cycle(withReceipts = true)

    val afterRules = store.get.unsafeRunSync()
    afterRules.map(_.name) shouldBe List("Rent 16.06.2026")
    val comment = afterRules.head.comment.getOrElse("")
    comment should include(receiptMarker.value)
    comment should include(ruleFileMarker.value)

    // Stage 4 — identical re-run: pure no-op. Every item is a skip, and apply writes nothing.
    PlanProgram.run[IO](csvPath, receipts).unsafeRunSync()
    savedPlan.get.unsafeRunSync().items.map(_.action).foreach {
      case PlanAction.SkipDuplicate(_, _, _) | PlanAction.ReceiptAlreadyUploaded(_, _) => ()
      case other => fail(s"unexpected non-skip action in a converged re-run: $other")
    }

    val createsBefore = creates.get.unsafeRunSync()
    val editsBefore = edits.get.unsafeRunSync()
    ApplyProgram.run[IO].unsafeRunSync()
    (creates.get.unsafeRunSync(), edits.get.unsafeRunSync()) shouldBe (createsBefore, editsBefore)

    // Over the whole sequence: exactly one create and never a duplicate.
    creates.get.unsafeRunSync() shouldBe 1
    store.get.unsafeRunSync() should have size 1
  }
end IdempotencyRoundTripTest
