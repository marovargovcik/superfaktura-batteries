package superfaktura.cli.bank

import superfaktura.CliError
import superfaktura.bank.{BankStatementSourceAlgebra, TatraBankaCsv, Transaction}

import cats.ApplicativeThrow
import cats.effect.Async
import cats.syntax.all.*
import fs2.data.csv.{CsvRow, lowlevel}
import fs2.io.file.{Files, Path as FsPath}
import fs2.text

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path

object TatraBankaSource:

  private val windows1250 = Charset.forName("windows-1250")

  given live[F[_]: Async]: BankStatementSourceAlgebra[F] with
    override def read(path: Path): F[List[Transaction]] =
      Files
        .forAsync[F]
        .readAll(FsPath.fromNioPath(path))
        .through(text.decodeWithCharset(windows1250))
        .through(lowlevel.rows[F, String]())
        .through(lowlevel.headers[F, String])
        .evalMap(toTransaction[F])
        .compile
        .toList
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }

  private def toTransaction[F[_]: ApplicativeThrow](row: CsvRow[String]): F[Transaction] =
    TatraBankaCsv.parseRow(row.toMap).leftMap[Throwable](CliError.CsvInvalid(_)).liftTo[F]
