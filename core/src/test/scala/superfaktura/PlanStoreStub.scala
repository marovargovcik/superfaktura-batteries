package superfaktura

trait PlanStoreStub[F[_]] extends PlanStore[F]:
  override def save(plan: Plan): F[Unit] = ???
  override def load: F[Plan] = ???
