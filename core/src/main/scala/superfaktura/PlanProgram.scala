package superfaktura

import cats.MonadThrow
import cats.syntax.all.*

import java.nio.file.Path

object PlanProgram:

  def run[F[_]: MonadThrow](csv: Path)(using
      bank: BankStatementSourceAlgebra[F],
      superfaktura: SuperfakturaAlgebra[F],
      store: PlanStore[F],
      reporter: ReporterAlgebra[F]
  ): F[Unit] =
    for
      transactions <- bank.read(csv)
      candidates = ExpensePlanner.toCandidates(transactions)
      existing <-
        if candidates.isEmpty then List.empty[Expense].pure[F]
        else superfaktura.listExpenses(ExpensePlanner.windowOf(candidates))
      plan = ExpensePlanner.buildPlan(ExpensePlanner.triage(candidates, existing))
      _ <- store.save(plan)
      _ <- reporter.summary(plan)
    yield ()
