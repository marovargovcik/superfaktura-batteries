package superfaktura.cli

import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import fs2.text
import io.circe.parser.decode
import io.circe.syntax.*
import superfaktura.{CliError, Plan, PlanStore}

import java.nio.file.Path

object FilePlanStore:

  def at[F[_]: Async](path: Path): PlanStore[F] = new PlanStore[F]:
    def save(plan: Plan): F[Unit] =
      Stream
        .emit(plan.asJson.spaces2)
        .through(text.utf8.encode)
        .through(Files.forAsync[F].writeAll(FsPath.fromNioPath(path)))
        .compile
        .drain

    def load: F[Plan] =
      Files
        .forAsync[F]
        .readAll(FsPath.fromNioPath(path))
        .through(text.utf8.decode)
        .compile
        .string
        .flatMap(content => decode[Plan](content).leftMap[Throwable](error => CliError.PlanInvalid(error.getMessage)).liftTo[F])
