package superfaktura.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import pureconfig.ConfigSource
import superfaktura.CliError

import java.nio.file.Path

object Main
    extends CommandIOApp(
      name = "superfaktura-batteries",
      header = "Bookkeeping CLI for Superfaktura.sk"
    ):

  private val csv: Opts[Option[Path]] =
    Opts.option[Path]("csv", "Bank statement CSV (Tatra banka export).").orNone

  private val receipts: Opts[Option[Path]] =
    Opts.option[Path]("receipts", "Folder of receipts/invoices to attach.").orNone

  private val planCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("plan", "Analyse inputs and write a reviewable plan (no changes are made).") {
      (csv, receipts).mapN(runPlan)
    }

  private val applyCommand: Opts[IO[ExitCode]] =
    Opts.subcommand("apply", "Execute a reviewed plan.") {
      Opts.option[Path]("plan", "Plan JSON to execute.").map(runApply)
    }

  override def main: Opts[IO[ExitCode]] = planCommand orElse applyCommand

  private def loadConfig: IO[AppConfig] =
    IO.fromEither(ConfigSource.default.load[AppConfig].leftMap(failures => CliError.ConfigInvalid(failures.toString)))

  private def runPlan(csv: Option[Path], receipts: Option[Path]): IO[ExitCode] =
    loadConfig
      .flatMap(config => IO.println(s"plan: csv=$csv receipts=$receipts company=${config.superfaktura.companyId}"))
      .as(ExitCode.Success)

  private def runApply(plan: Path): IO[ExitCode] =
    IO.println(s"apply: plan=$plan").as(ExitCode.Success)
end Main
