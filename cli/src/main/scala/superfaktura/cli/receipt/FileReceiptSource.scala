package superfaktura.cli.receipt

import java.io.IOException
import java.nio.file.Path

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import scodec.bits.ByteVector
import superfaktura.CliError
import superfaktura.receipt.{AttachmentFormat, ReceiptBytes, ReceiptFile, ReceiptRef, ReceiptSourceAlgebra}

object FileReceiptSource:

  given live[F[_]: Async]: ReceiptSourceAlgebra[F] with
    override def list(folder: Path): F[List[ReceiptFile]] =
      Files
        .forAsync[F]
        .list(FsPath.fromNioPath(folder))
        .evalFilter(Files.forAsync[F].isRegularFile(_))
        .filter(path => AttachmentFormat.fromExtension(path.extName.stripPrefix(".")).isDefined)
        .evalMap(path => Files.forAsync[F].size(path).map(size => ReceiptFile(ReceiptRef(path.toString), size)))
        .compile
        .toList
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }

    override def load(ref: ReceiptRef): F[ReceiptBytes] =
      Files
        .forAsync[F]
        .readAll(FsPath(ref.path))
        .compile
        .to(ByteVector)
        .map(ReceiptBytes(_))
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }

    override def exists(ref: ReceiptRef): F[Boolean] =
      Files.forAsync[F].exists(FsPath(ref.path))
