package superfaktura.cli

import cats.effect.std.Console
import superfaktura.ReporterAlgebra
import superfaktura.plan.{ExpensePlanner, Plan}

object ConsoleReporter:

  given live[F[_]: Console]: ReporterAlgebra[F] with
    override def summary(plan: Plan): F[Unit] = Console[F].println(ExpensePlanner.render(plan))
