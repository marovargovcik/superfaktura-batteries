package superfaktura

import cats.MonadThrow
import cats.syntax.all.*

object ApplyProgram:

  def run[F[_]: MonadThrow](using
      store: PlanStore[F],
      superfaktura: SuperfakturaAlgebra[F],
      reporter: ReporterAlgebra[F]
  ): F[Unit] =
    for
      plan <- store.load
      applied <- plan.items.traverse(applyItem)
      updated = Plan(applied)
      _ <- store.save(updated)
      _ <- reporter.summary(updated)
    yield ()

  private def applyItem[F[_]: MonadThrow](item: PlanItem)(using superfaktura: SuperfakturaAlgebra[F]): F[PlanItem] =
    item match
      case PlanItem(PlanAction.CreateExpense(ref, candidate, _), PlanItemStatus.Pending) =>
        superfaktura
          .addExpense(ExpensePlanner.newExpense(ref, candidate))
          .as(item.copy(status = PlanItemStatus.Applied))
      case other => other.pure[F]
