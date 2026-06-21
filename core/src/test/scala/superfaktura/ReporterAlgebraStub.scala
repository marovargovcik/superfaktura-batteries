package superfaktura

import superfaktura.plan.Plan

trait ReporterAlgebraStub[F[_]] extends ReporterAlgebra[F]:
  override def summary(plan: Plan): F[Unit] = ???
