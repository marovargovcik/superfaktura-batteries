package superfaktura.cli

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FsPath}
import scodec.bits.ByteVector
import superfaktura.{CliError, ReceiptBytes, ReceiptFile, ReceiptRef, ReceiptSourceAlgebra}

import java.io.IOException
import java.nio.file.Path

object FileReceiptSource:

  private val supportedExtensions = Set("pdf", "jpg", "jpeg", "png", "heic")

  given live[F[_]: Async]: ReceiptSourceAlgebra[F] with
    override def list(folder: Path): F[List[ReceiptFile]] =
      Files
        .forAsync[F]
        .list(FsPath.fromNioPath(folder))
        .evalFilter(Files.forAsync[F].isRegularFile(_))
        .filter(path => supportedExtensions.contains(path.extName.toLowerCase.stripPrefix(".")))
        .evalMap(path => Files.forAsync[F].size(path).map(size => ReceiptFile(ReceiptRef(path.toString), size)))
        .compile
        .toList
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }

    override def load(ref: ReceiptRef): F[ReceiptBytes] =
      Files
        .forAsync[F]
        .readAll(FsPath(ref.path))
        .compile
        .toVector
        .map(bytes => ReceiptBytes(ByteVector(bytes.toArray)))
        .adaptError { case error: IOException => CliError.FileAccess(error.getMessage) }
