package superfaktura

trait ReporterAlgebra[F[_]]:
  def summary(plan: Plan): F[Unit]
