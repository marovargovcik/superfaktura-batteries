package superfaktura

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ApplyProgramTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 16)
  private val candidate = CandidateExpense(ExternalRef("r1"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)

  "ApplyProgram.run creates pending expenses, marks them applied, and leaves duplicates untouched" in {
    val plan = Plan(
      List(
        PlanItem(PlanAction.CreateExpense(candidate.externalRef, candidate, None), PlanItemStatus.Pending),
        PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(9)), PlanItemStatus.Skipped)
      )
    )
    val saved = Ref.unsafe[IO, Option[Plan]](None)
    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def load: IO[Plan] = IO.pure(plan)
      override def save(updated: Plan): IO[Unit] = saved.set(Some(updated))
    val added = Ref.unsafe[IO, List[NewExpense]](Nil)
    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
      override def addExpense(request: NewExpense): IO[ExpenseId] = added.update(_ :+ request).as(ExpenseId(100))
    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    ApplyProgram.run[IO].unsafeRunSync()

    val requests = added.get.unsafeRunSync()
    requests.map(_.name) shouldBe List("ORANGE")
    requests.head.comment shouldBe Some("sfref:r1")
    saved.get.unsafeRunSync().getOrElse(fail("plan was not saved")).items.map(_.status) shouldBe
      List(PlanItemStatus.Applied, PlanItemStatus.Skipped)
  }

  "ApplyProgram.run is idempotent: an already-applied create is not re-created" in {
    val plan =
      Plan(List(PlanItem(PlanAction.CreateExpense(candidate.externalRef, candidate, None), PlanItemStatus.Applied)))
    given PlanStore[IO] = new PlanStoreStub[IO]:
      override def load: IO[Plan] = IO.pure(plan)
      override def save(updated: Plan): IO[Unit] = IO.unit
    val added = Ref.unsafe[IO, List[NewExpense]](Nil)
    given SuperfakturaAlgebra[IO] = new SuperfakturaAlgebraStub[IO]:
      override def addExpense(request: NewExpense): IO[ExpenseId] = added.update(_ :+ request).as(ExpenseId(100))
    given ReporterAlgebra[IO] = new ReporterAlgebraStub[IO]:
      override def summary(plan: Plan): IO[Unit] = IO.unit

    ApplyProgram.run[IO].unsafeRunSync()

    added.get.unsafeRunSync() shouldBe empty
  }
end ApplyProgramTest
