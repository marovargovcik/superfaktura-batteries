package superfaktura

trait PlanStore[F[_]]:
  def save(plan: Plan): F[Unit]
  def load: F[Plan]
