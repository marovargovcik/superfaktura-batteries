package superfaktura.cli

import cats.effect.Sync
import cats.syntax.all.*
import fs2.Stream
import fs2.data.csv.{CsvRow, lowlevel}
import superfaktura.{BankStatementSourceAlgebra, CliError, TatraBankaCsv, Transaction}

import java.nio.charset.Charset
import java.nio.file.{Files, Path}

object TatraBankaSource:

  private val windows1250 = Charset.forName("windows-1250")

  given live[F[_]: Sync]: BankStatementSourceAlgebra[F] with
    def read(path: Path): F[List[Transaction]] =
      Sync[F].blocking(String(Files.readAllBytes(path), windows1250)).flatMap(parse[F])

  private def parse[F[_]: Sync](content: String): F[List[Transaction]] =
    Stream
      .emit(content)
      .covary[F]
      .through(lowlevel.rows[F, String]())
      .through(lowlevel.headers[F, String])
      .evalMap(toTransaction[F])
      .compile
      .toList

  private def toTransaction[F[_]: Sync](row: CsvRow[String]): F[Transaction] =
    TatraBankaCsv.parseRow(row.toMap) match
      case Right(transaction) => transaction.pure[F]
      case Left(error)        => Sync[F].raiseError(CliError.CsvInvalid(error))
