package superfaktura

trait ReporterAlgebraStub[F[_]] extends ReporterAlgebra[F]:
  override def summary(plan: Plan): F[Unit] = ???
