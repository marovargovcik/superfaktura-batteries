package superfaktura.cli.rule

import java.io.IOException
import java.nio.file.Path

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import fs2.text
import io.circe.parser.decode
import superfaktura.CliError
import superfaktura.rule.{RuleSet, RuleStore}

object FileRuleStore:

  def at[F[_]: Async](path: Path): RuleStore[F] = new RuleStore[F]:
    override def load: F[RuleSet] =
      Files
        .forAsync[F]
        .readAll(FsPath.fromNioPath(path))
        .through(text.utf8.decode)
        .compile
        .string
        .flatMap(parse)
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }

    private def parse(content: String): F[RuleSet] =
      decode[RuleSet](content)
        .leftMap[Throwable](error => CliError.RulesInvalid(error.getMessage))
        .flatMap(validate)
        .liftTo[F]

    // A rule that neither renames nor attaches would silently do nothing, so reject it at load.
    private def validate(ruleSet: RuleSet): Either[Throwable, RuleSet] =
      if ruleSet.rules.exists(rule => rule.rename.isEmpty && rule.attach.isEmpty) then
        Left(CliError.RulesInvalid("a rule must set rename, attach, or both"))
      else Right(ruleSet)
