package superfaktura

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector

import java.nio.file.{Path, Paths}
import java.time.LocalDate

class PlanProgramTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)
  private val anyCsv = Some(Paths.get("ignored.csv"))
  private val anyReceipts = Some(Paths.get("ignored-receipts"))

  private def debit(amount: String, recipientInfo: Option[String], description: String): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = TransactionType.Debit,
      counterpartyIban = None,
      variableSymbol = None,
      specificSymbol = None,
      recipientInfo = recipientInfo,
      description = description
    )

  private def bankReturning(transactions: List[Transaction]): BankStatementSourceAlgebra[IO] =
    new BankStatementSourceAlgebraStub[IO]:
      override def read(path: Path): IO[List[Transaction]] = IO.pure(transactions)

  private def lists(expenses: List[Expense]): SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
    override def listExpenses(window: DateWindow): IO[List[Expense]] = IO.pure(expenses)

  private def receiptsFolder(refs: List[ReceiptRef]): ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO]:
    override def list(folder: Path): IO[List[ReceiptFile]] = IO.pure(refs.map(ReceiptFile(_, 100L)))
    override def load(ref: ReceiptRef): IO[ReceiptBytes] = IO.pure(ReceiptBytes(ByteVector(1)))

  private def ocrReturning(amount: String, on: LocalDate): OcrAlgebra[IO] = new OcrAlgebraStub[IO]:
    override def read(receipt: ReceiptBytes, media: ReceiptMedia): IO[OcrResult] =
      IO.pure(OcrResult(Some(Money(BigDecimal(amount), "EUR")), Some(on)))

  private given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
    override def summary(plan: Plan): IO[Unit] = IO.unit

  private def savedBy(saved: Ref[IO, Option[Plan]]): PlanStore[IO] = new PlanStoreStub[IO]:
    override def save(plan: Plan): IO[Unit] = saved.set(Some(plan))

  "PlanProgram.run" - {
    "with a CSV and no receipts, creates fresh expenses and skips ref-matched duplicates" in {
      val card =
        debit("73.71", Some("423473******7299 BA MAREK 20260613 16:13:59 73.71EUR SHELL 8203"), "GP NÁKUP POS")
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val shellRef = ExpensePlanner.toCandidates(List(card)).head.externalRef
      val saved = Ref.unsafe[IO, Option[Plan]](None)

      given BankStatementSourceAlgebra[IO] = bankReturning(List(card, transfer))
      given SuperfakturaAlgebra[IO] = lists(
        List(Expense(
          ExpenseId(9),
          "SHELL",
          Money(BigDecimal("73.71"), "EUR"),
          date,
          Some(ExpensePlanner.refMarker(shellRef))
        ))
      )
      // receipts=None, so the receipt source and OCR are never consulted (left as ???).
      given ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO] {}
      given OcrAlgebra[IO] = new OcrAlgebraStub[IO] {}
      given PlanStore[IO] = savedBy(saved)

      PlanProgram.run[IO](anyCsv, None).unsafeRunSync()

      val plan = saved.get.unsafeRunSync().getOrElse(fail("plan was not saved"))
      plan.items.collect {
        case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
      } shouldBe List("UHRADA POISTNEHO" -> None)
      plan.items.collect {
        case PlanItem(PlanAction.SkipDuplicate(_, _, matched), _) => matched
      } shouldBe List(ExpenseId(9))
    }

    "with a CSV and receipts, attaches a matched receipt to the new expense it pairs with" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val saved = Ref.unsafe[IO, Option[Plan]](None)

      given BankStatementSourceAlgebra[IO] = bankReturning(List(transfer))
      given SuperfakturaAlgebra[IO] = lists(Nil)
      given ReceiptSourceAlgebra[IO] = receiptsFolder(List(ReceiptRef("orange.jpg")))
      given OcrAlgebra[IO] = ocrReturning("45.45", date.minusDays(1))
      given PlanStore[IO] = savedBy(saved)

      PlanProgram.run[IO](anyCsv, anyReceipts).unsafeRunSync()

      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.collect {
        case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
      } shouldBe List("UHRADA POISTNEHO" -> Some(ReceiptRef("orange.jpg")))
    }

    "with receipts and no CSV, attaches matched receipts to existing expenses" in {
      val existing = Expense(ExpenseId(5), "ACME", Money(BigDecimal("20.00"), "EUR"), date, None)
      val saved = Ref.unsafe[IO, Option[Plan]](None)

      // bank is never read (no CSV), left as ???.
      given BankStatementSourceAlgebra[IO] = new BankStatementSourceAlgebraStub[IO] {}
      given SuperfakturaAlgebra[IO] = lists(List(existing))
      given ReceiptSourceAlgebra[IO] = receiptsFolder(List(ReceiptRef("inv.pdf")))
      given OcrAlgebra[IO] = ocrReturning("20.00", date.minusDays(1))
      given PlanStore[IO] = savedBy(saved)

      PlanProgram.run[IO](None, anyReceipts).unsafeRunSync()

      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.collect {
        case PlanItem(PlanAction.AttachToExisting(id, ref), _) => id -> ref
      } shouldBe List(ExpenseId(5) -> ReceiptRef("inv.pdf"))
    }

    "flags a receipt that matches no transaction" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val saved = Ref.unsafe[IO, Option[Plan]](None)

      given BankStatementSourceAlgebra[IO] = bankReturning(List(transfer))
      given SuperfakturaAlgebra[IO] = lists(Nil)
      given ReceiptSourceAlgebra[IO] = receiptsFolder(List(ReceiptRef("mystery.png")))
      given OcrAlgebra[IO] = ocrReturning("999.99", date)
      given PlanStore[IO] = savedBy(saved)

      PlanProgram.run[IO](anyCsv, anyReceipts).unsafeRunSync()

      saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.collect {
        case PlanItem(PlanAction.FlagReceipt(ref, _), _) => ref
      } shouldBe List(ReceiptRef("mystery.png"))
    }
  }
end PlanProgramTest
