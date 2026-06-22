package superfaktura.rule

import cats.Applicative
import cats.syntax.all.*

trait RuleStore[F[_]]:
  def load: F[RuleSet]

object RuleStore:
  def empty[F[_]: Applicative]: RuleStore[F] = new RuleStore[F]:
    override def load: F[RuleSet] = RuleSet.empty.pure[F]
