package superfaktura

import java.nio.file.{Path, Paths}
import java.time.LocalDate

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.bank.{BankStatementSourceAlgebra, BankStatementSourceAlgebraStub, Transaction, TransactionType}
import superfaktura.expense.{Expense, ExpenseId, SuperfakturaAlgebra, SuperfakturaAlgebraStub}
import superfaktura.plan.{ExpensePlanner, Plan, PlanAction, PlanItem, PlanItemStatus, PlanStore, PlanStoreStub}
import superfaktura.receipt.{
  OcrAlgebra,
  OcrAlgebraStub,
  OcrResult,
  ReceiptBytes,
  ReceiptFile,
  ReceiptMedia,
  ReceiptRef,
  ReceiptSourceAlgebra,
  ReceiptSourceAlgebraStub
}
import superfaktura.rule.{Rule, RuleMatch, RuleSet, RuleStore}

class PlanProgramTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)
  private val csvPath = Paths.get("ignored.csv")
  private val receiptsPath = Some(Paths.get("ignored-receipts"))

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

  private def attachmentsExist(present: Boolean): ReceiptSourceAlgebra[IO] = new ReceiptSourceAlgebraStub[IO]:
    override def exists(ref: ReceiptRef): IO[Boolean] = IO.pure(present)
    override def load(ref: ReceiptRef): IO[ReceiptBytes] = IO.pure(ReceiptBytes(ByteVector(1)))

  private def ocrReturning(amount: String, on: LocalDate): OcrAlgebra[IO] = new OcrAlgebraStub[IO]:
    override def read(receipt: ReceiptBytes, media: ReceiptMedia): IO[OcrResult] =
      IO.pure(OcrResult(Some(Money(BigDecimal(amount), "EUR")), Some(on)))

  private given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
    override def summary(plan: Plan): IO[Unit] = IO.unit

  private given RuleStore[IO] = RuleStore.empty[IO]

  private def rulesOf(rules: Rule*): RuleStore[IO] = new RuleStore[IO]:
    override def load: IO[RuleSet] = IO.pure(RuleSet(rules.toList))

  private def savedBy(saved: Ref[IO, Option[Plan]]): PlanStore[IO] = new PlanStoreStub[IO]:
    override def save(plan: Plan): IO[Unit] = saved.set(Some(plan))

  "PlanProgram.run" - {
    "with no receipts, creates fresh expenses and skips ref-matched duplicates" in {
      val card =
        debit("73.71", Some("423473******7299 BA MAREK 20260613 16:13:59 73.71EUR SHELL 8203"), "GP NÁKUP POS")
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val shellRef = ExpensePlanner.toCandidates(List(card)).head.externalRef
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(card, transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(
            lists(
              List(Expense(
                ExpenseId(9),
                "SHELL",
                Money(BigDecimal("73.71"), "EUR"),
                date,
                Some(ExpensePlanner.refMarker(shellRef))
              ))
            )
          )
          given ReceiptSourceAlgebra[IO] <- IO.pure(new ReceiptSourceAlgebraStub[IO] {})
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield
          plan.items.collect {
            case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
          } shouldBe List("UHRADA POISTNEHO" -> None)
          plan.items.collect {
            case PlanItem(PlanAction.SkipDuplicate(_, _, matched), _) => matched
          } shouldBe List(ExpenseId(9))
      test.unsafeRunSync()
    }

    "attaches a matched receipt to the new expense it pairs with" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("orange.jpg"))))
          given OcrAlgebra[IO] <- IO.pure(ocrReturning("45.45", date.minusDays(1)))
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
        } shouldBe List("UHRADA POISTNEHO" -> Some(ReceiptRef("orange.jpg")))
      test.unsafeRunSync()
    }

    "skips a duplicate transaction and attaches its receipt to the existing expense" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val ref = ExpensePlanner.toCandidates(List(transfer)).head.externalRef
      val existing =
        Expense(ExpenseId(5), "UHRADA", Money(BigDecimal("45.45"), "EUR"), date, Some(ExpensePlanner.refMarker(ref)))
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("inv.pdf"))))
          given OcrAlgebra[IO] <- IO.pure(ocrReturning("45.45", date.minusDays(1)))
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect { case PlanItem(PlanAction.SkipDuplicate(_, _, matched), _) => matched } shouldBe List(
            ExpenseId(5)
          )
          items.collect { case PlanItem(PlanAction.AttachToExisting(id, r, _), _) => id -> r } shouldBe
            List(ExpenseId(5) -> ReceiptRef("inv.pdf"))
          items.collect { case PlanItem(PlanAction.CreateExpense(_, _, _), _) => () } shouldBe empty
      test.unsafeRunSync()
    }

    "flags a HEIC receipt as unreadable without loading it or calling OCR" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          // load and OCR are left as ??? — a HEIC must reach neither.
          given ReceiptSourceAlgebra[IO] <- IO.pure(new ReceiptSourceAlgebraStub[IO]:
            override def list(folder: Path): IO[List[ReceiptFile]] =
              IO.pure(List(ReceiptFile(ReceiptRef("photo.heic"), 100L)))
          )
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.FlagReceipt(ref, _), _) => ref
        } shouldBe List(ReceiptRef("photo.heic"))
      test.unsafeRunSync()
    }

    "flags a receipt whose OCR could not read both amount and date" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("partial.jpg"))))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO]:
            override def read(receipt: ReceiptBytes, media: ReceiptMedia): IO[OcrResult] =
              IO.pure(OcrResult(Some(Money(BigDecimal("45.45"), "EUR")), None))
          )
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.FlagReceipt(ref, _), _) => ref
        } shouldBe List(ReceiptRef("partial.jpg"))
      test.unsafeRunSync()
    }

    "flags a receipt that matches no expense" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("mystery.png"))))
          given OcrAlgebra[IO] <- IO.pure(ocrReturning("999.99", date))
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.FlagReceipt(ref, _), _) => ref
        } shouldBe List(ReceiptRef("mystery.png"))
      test.unsafeRunSync()
    }

    "flags a receipt as already-uploaded if its marker is recorded in an existing expense note" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      // Marker of the receipt file that will be loaded as ByteVector(1).
      val receiptMarker = ExpensePlanner.receiptMarker(ReceiptBytes(ByteVector(1)))
      val existing = Expense(ExpenseId(20), "PREV", Money(BigDecimal("10.00"), "EUR"), date, Some(receiptMarker.value))
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("orange.jpg"))))
          given OcrAlgebra[IO] <- IO.pure(ocrReturning("45.45", date.minusDays(1)))
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect {
            case PlanItem(PlanAction.ReceiptAlreadyUploaded(ref, id), status) => (ref, id, status)
          } shouldBe List((ReceiptRef("orange.jpg"), ExpenseId(20), PlanItemStatus.Skipped))
          items.collect { case PlanItem(PlanAction.AttachToExisting(_, _, _), _) => () } shouldBe empty
      test.unsafeRunSync()
    }

    "does not re-attach a receipt already uploaded to the existing expense it matches" in {
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
      val ref = ExpensePlanner.toCandidates(List(transfer)).head.externalRef
      val receiptMarker = ExpensePlanner.receiptMarker(ReceiptBytes(ByteVector(1)))
      val comment = ExpensePlanner.appendMarker(Some(ExpensePlanner.refMarker(ref)), receiptMarker)
      val existing = Expense(ExpenseId(7), "UHRADA", Money(BigDecimal("45.45"), "EUR"), date, comment)
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(transfer)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(receiptsFolder(List(ReceiptRef("inv.pdf"))))
          given OcrAlgebra[IO] <- IO.pure(ocrReturning("45.45", date.minusDays(1)))
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          _ <- PlanProgram.run[IO](csvPath, receiptsPath)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect {
            case PlanItem(PlanAction.ReceiptAlreadyUploaded(r, id), status) => (r, id, status)
          } shouldBe List((ReceiptRef("inv.pdf"), ExpenseId(7), PlanItemStatus.Skipped))
          items.collect { case PlanItem(PlanAction.AttachToExisting(_, _, _), _) => () } shouldBe empty
      test.unsafeRunSync()
    }

    "renames a matching transaction using the rule template, leaving others on their derived name" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba 8180")
      val other = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 9000")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent, other)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(new ReceiptSourceAlgebraStub[IO] {})
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.CreateExpense(_, candidate, _), _) => candidate.name
        } shouldBe List("Rent 16.06.2026", "UHRADA POISTNEHO")
      test.unsafeRunSync()
    }

    "attaches a rule's fixed file to the new expense it matches, without scanning receipts" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(attachmentsExist(present = true))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(
            rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/rent.pdf")))
          )
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
        } shouldBe List("LANDLORD" -> Some(ReceiptRef("/invoices/rent.pdf")))
      test.unsafeRunSync()
    }

    "flags a rule attachment whose file is missing, and creates the expense without it" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba 8180")
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(Nil))
          given ReceiptSourceAlgebra[IO] <- IO.pure(attachmentsExist(present = false))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(
            rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/gone.pdf")))
          )
          _ <- PlanProgram.run[IO](csvPath, None)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect {
            case PlanItem(PlanAction.CreateExpense(_, candidate, attach), _) => candidate.name -> attach
          } shouldBe List("LANDLORD" -> None)
          items.collect { case PlanItem(PlanAction.FlagReceipt(ref, _), _) => ref } shouldBe List(
            ReceiptRef("/invoices/gone.pdf")
          )
      test.unsafeRunSync()
    }

    "renames an already-booked expense when a rule's name differs from Superfaktura" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba")
      val ref = ExpensePlanner.toCandidates(List(rent)).head.externalRef
      val existing =
        Expense(ExpenseId(5), "LANDLORD", Money(BigDecimal("450.00"), "EUR"), date, Some(ExpensePlanner.refMarker(ref)))
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(new ReceiptSourceAlgebraStub[IO] {})
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.RenameExpense(id, name), _) => id -> name
        } shouldBe List(ExpenseId(5) -> "Rent 16.06.2026")
      test.unsafeRunSync()
    }

    "leaves an already-booked expense alone when its name already matches the rule" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba")
      val ref = ExpensePlanner.toCandidates(List(rent)).head.externalRef
      val existing = Expense(
        ExpenseId(5),
        "Rent 16.06.2026",
        Money(BigDecimal("450.00"), "EUR"),
        date,
        Some(ExpensePlanner.refMarker(ref))
      )
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(new ReceiptSourceAlgebraStub[IO] {})
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))
          _ <- PlanProgram.run[IO](csvPath, None)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect { case PlanItem(PlanAction.RenameExpense(_, _), _) => () } shouldBe empty
          items.collect { case PlanItem(PlanAction.SkipDuplicate(_, _, id), _) => id } shouldBe List(ExpenseId(5))
      test.unsafeRunSync()
    }

    "attaches a rule's fixed file to an already-booked expense when it isn't attached yet" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba")
      val ref = ExpensePlanner.toCandidates(List(rent)).head.externalRef
      val existing =
        Expense(ExpenseId(5), "LANDLORD", Money(BigDecimal("450.00"), "EUR"), date, Some(ExpensePlanner.refMarker(ref)))
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(attachmentsExist(present = true))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(
            rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/rent.pdf")))
          )
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.AttachToExisting(id, attachment, _), _) => id -> attachment
        } shouldBe List(ExpenseId(5) -> ReceiptRef("/invoices/rent.pdf"))
      test.unsafeRunSync()
    }

    "does not re-attach a rule file already recorded on the existing expense" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba")
      val ref = ExpensePlanner.toCandidates(List(rent)).head.externalRef
      val marker = ExpensePlanner.receiptMarker(ReceiptBytes(ByteVector(1)))
      val comment = ExpensePlanner.appendMarker(Some(ExpensePlanner.refMarker(ref)), marker)
      val existing = Expense(ExpenseId(5), "LANDLORD", Money(BigDecimal("450.00"), "EUR"), date, comment)
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(attachmentsExist(present = true))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(
            rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/rent.pdf")))
          )
          _ <- PlanProgram.run[IO](csvPath, None)
          plan <- saved.get.map(_.getOrElse(fail("plan was not saved")))
        yield plan.items.collect {
          case PlanItem(PlanAction.AttachToExisting(_, _, _), _) => ()
        } shouldBe empty
      test.unsafeRunSync()
    }

    "flags a rule attachment for an already-booked expense when the file is missing" in {
      val rent = debit("450.00", Some("LANDLORD"), "Platba")
      val ref = ExpensePlanner.toCandidates(List(rent)).head.externalRef
      val existing =
        Expense(ExpenseId(5), "LANDLORD", Money(BigDecimal("450.00"), "EUR"), date, Some(ExpensePlanner.refMarker(ref)))
      val test =
        for
          saved <- Ref.of[IO, Option[Plan]](None)
          given BankStatementSourceAlgebra[IO] <- IO.pure(bankReturning(List(rent)))
          given SuperfakturaAlgebra[IO] <- IO.pure(lists(List(existing)))
          given ReceiptSourceAlgebra[IO] <- IO.pure(attachmentsExist(present = false))
          given OcrAlgebra[IO] <- IO.pure(new OcrAlgebraStub[IO] {})
          given PlanStore[IO] <- IO.pure(savedBy(saved))
          given RuleStore[IO] <- IO.pure(
            rulesOf(Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/gone.pdf")))
          )
          _ <- PlanProgram.run[IO](csvPath, None)
          items <- saved.get.map(_.getOrElse(fail("plan was not saved")).items)
        yield
          items.collect { case PlanItem(PlanAction.AttachToExisting(_, _, _), _) => () } shouldBe empty
          items.collect { case PlanItem(PlanAction.FlagReceipt(r, _), _) => r } shouldBe List(
            ReceiptRef("/invoices/gone.pdf")
          )
      test.unsafeRunSync()
    }
  }
end PlanProgramTest
