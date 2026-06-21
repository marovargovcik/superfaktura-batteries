package superfaktura.cli

import superfaktura.ReporterAlgebra
import superfaktura.plan.{ExpensePlanner, Plan}

import cats.effect.std.Console

object ConsoleReporter:

  given live[F[_]: Console]: ReporterAlgebra[F] with
    override def summary(plan: Plan): F[Unit] = Console[F].println(ExpensePlanner.render(plan))
