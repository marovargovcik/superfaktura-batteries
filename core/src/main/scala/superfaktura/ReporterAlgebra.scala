package superfaktura

import superfaktura.plan.Plan

trait ReporterAlgebra[F[_]]:
  def summary(plan: Plan): F[Unit]
