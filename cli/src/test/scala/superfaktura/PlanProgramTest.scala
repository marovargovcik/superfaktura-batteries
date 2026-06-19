package superfaktura

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import java.time.LocalDate

class PlanProgramTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)

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

  "PlanProgram.run plans fresh candidates as creates and ref-matched existing expenses as skips" in {
    val card =
      debit("73.71", Some("423473******7299 BRATISLAVSKA MAREK 20260613 16:13:59 73.71EUR SHELL 8203"), "GP NÁKUP POS")
    val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
    val transactions = List(card, transfer)
    val shellRef = ExpensePlanner.toCandidates(transactions).find(_.name == "SHELL 8203").get.externalRef

    given BankStatementSourceAlgebra[IO] = new BankStatementSourceAlgebraStub[IO]:
      override def read(path: Path): IO[List[Transaction]] = IO.pure(transactions)
    val queried = Ref.unsafe[IO, Option[DateWindow]](None)
    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
      override def listExpenses(window: DateWindow): IO[List[Expense]] =
        queried.set(Some(window)).as(
          List(
            Expense(
              id = ExpenseId(9),
              name = "SHELL",
              amount = Money(BigDecimal("73.71"), "EUR"),
              created = date,
              comment = Some(ExpensePlanner.refMarker(shellRef))
            )
          )
        )
    val saved = Ref.unsafe[IO, Option[Plan]](None)
    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def save(plan: Plan): IO[Unit] = saved.set(Some(plan))
    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    PlanProgram.run[IO](Paths.get("ignored.csv")).unsafeRunSync()

    queried.get.unsafeRunSync() shouldBe Some(DateWindow(date, date))
    val plan = saved.get.unsafeRunSync().getOrElse(fail("plan was not saved"))
    plan.items.collect {
      case PlanItem(PlanAction.CreateExpense(_, candidate, _), PlanItemStatus.Pending) => candidate.name
    } shouldBe List("UHRADA POISTNEHO")
    plan.items.collect {
      case PlanItem(PlanAction.SkipDuplicate(_, _, matched), PlanItemStatus.Skipped) => matched
    } shouldBe List(ExpenseId(9))
  }

  "PlanProgram.run skips the listing entirely and saves an empty plan when there are no debits" in {
    given BankStatementSourceAlgebra[IO] = new BankStatementSourceAlgebraStub[IO]:
      override def read(path: Path): IO[List[Transaction]] = IO.pure(Nil)
    // listExpenses is left unimplemented (???): the test fails if the program lists for an empty CSV.
    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO] {}
    val saved = Ref.unsafe[IO, Option[Plan]](None)
    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def save(plan: Plan): IO[Unit] = saved.set(Some(plan))
    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    PlanProgram.run[IO](Paths.get("ignored.csv")).unsafeRunSync()

    saved.get.unsafeRunSync() shouldBe Some(Plan(Nil))
  }
end PlanProgramTest
