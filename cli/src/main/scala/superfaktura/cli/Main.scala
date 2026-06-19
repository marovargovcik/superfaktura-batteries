package superfaktura.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.http4s.client.Client
import org.http4s.client.middleware.{Retry, RetryPolicy}
import org.http4s.ember.client.EmberClientBuilder
import pureconfig.ConfigSource
import superfaktura.{
  ApplyProgram,
  BankStatementSourceAlgebra,
  CliError,
  PlanProgram,
  PlanStore,
  ReporterAlgebra,
  SuperfakturaAlgebra
}

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*

object Main
    extends CommandIOApp(
      name = "superfaktura-batteries",
      header = "Bookkeeping CLI for Superfaktura.sk"
    ):

  private val planPath: Opts[Path] =
    Opts
      .option[Path]("plan", "Plan JSON file (written by `plan`, executed by `apply`).")
      .withDefault(Paths.get("plan.json"))

  private val planCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("plan", "Analyse the CSV and write a reviewable plan (no changes are made).") {
      (Opts.option[Path]("csv", "Bank statement CSV (Tatra banka export)."), planPath).mapN(runPlan)
    }

  private val applyCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("apply", "Execute a reviewed plan.")(planPath.map(runApply))

  override def main: Opts[IO[ExitCode]] = planCommand orElse applyCommand

  private def runPlan(csv: Path, plan: Path): IO[ExitCode] =
    environment(plan)(PlanProgram.run[IO](csv)).as(ExitCode.Success)

  private def runApply(plan: Path): IO[ExitCode] =
    environment(plan)(ApplyProgram.run[IO]).as(ExitCode.Success)

  private def environment[A](plan: Path)(
      program: (BankStatementSourceAlgebra[IO], SuperfakturaAlgebra[IO], PlanStore[IO], ReporterAlgebra[IO]) ?=> IO[A]
  ): IO[A] =
    loadConfig.flatMap: config =>
      EmberClientBuilder.default[IO].build.use: ember =>
        given Client[IO] = Retry(retryPolicy)(ember)
        given SuperfakturaConfig = config.superfaktura
        given PlanStore[IO] = FilePlanStore.at[IO](plan)
        import SuperfakturaClient.given
        import TatraBankaSource.given
        import ConsoleReporter.given
        program

  // Retries only idempotent requests (the GET expense listing) on transient failures; the
  // create/edit POSTs are never retried, so a flaky response can't double-book an expense.
  private val retryPolicy: RetryPolicy[IO] =
    RetryPolicy(RetryPolicy.exponentialBackoff(maxWait = 10.seconds, maxRetry = 3))

  private def loadConfig: IO[AppConfig] =
    IO.fromEither(
      ConfigSource.default
        .load[AppConfig]
        .leftMap(failures => CliError.ConfigInvalid(failures.toList.map(_.description).mkString("; ")))
    )
end Main
