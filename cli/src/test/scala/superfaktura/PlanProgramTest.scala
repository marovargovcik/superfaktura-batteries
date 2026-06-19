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
      date,
      Money(BigDecimal(amount), "EUR"),
      TransactionType.Debit,
      None,
      None,
      None,
      recipientInfo,
      description
    )

  "PlanProgram.run plans fresh candidates as creates and ref-matched existing expenses as skips" in {
    val card =
      debit("73.71", Some("423473******7299 BRATISLAVSKA MAREK 20260613 16:13:59 73.71EUR SHELL 8203"), "GP NÁKUP POS")
    val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180")
    val transactions = List(card, transfer)
    val shellRef = ExpensePlanner.toCandidates(transactions).find(_.name == "SHELL 8203").get.externalRef

    given BankStatementSourceAlgebra[IO] = new BankStatementSourceAlgebraStub[IO]:
      override def read(path: Path): IO[List[Transaction]] = IO.pure(transactions)
    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
      override def listExpenses(window: DateWindow): IO[List[Expense]] =
        IO.pure(
          List(
            Expense(
              ExpenseId(9),
              "SHELL",
              Money(BigDecimal("73.71"), "EUR"),
              date,
              Some(ExpensePlanner.refMarker(shellRef))
            )
          )
        )
    val saved = Ref.unsafe[IO, Option[Plan]](None)
    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def save(plan: Plan): IO[Unit] = saved.set(Some(plan))
    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    PlanProgram.run[IO](Paths.get("ignored.csv")).unsafeRunSync()

    val plan = saved.get.unsafeRunSync().getOrElse(fail("plan was not saved"))
    plan.items.collect {
      case PlanItem(PlanAction.CreateExpense(_, candidate, _), PlanItemStatus.Pending) => candidate.name
    } shouldBe List("UHRADA POISTNEHO")
    plan.items.collect {
      case PlanItem(PlanAction.SkipDuplicate(_, _, matched), PlanItemStatus.Skipped) => matched
    } shouldBe List(ExpenseId(9))
  }
end PlanProgramTest
