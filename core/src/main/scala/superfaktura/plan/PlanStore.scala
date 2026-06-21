package superfaktura.plan

trait PlanStore[F[_]]:
  def save(plan: Plan): F[Unit]
  def load: F[Plan]
