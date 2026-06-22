package superfaktura.cli.receipt

import java.nio.file.{Files as JFiles, Path}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.CliError
import superfaktura.receipt.ReceiptRef

class FileReceiptSourceTest extends AnyFreeSpec with Matchers:

  private val algebra = FileReceiptSource.live[IO]

  private def filename(path: String): String = Path.of(path).getFileName.toString

  "list returns only supported receipt files with their sizes, ignoring others and subdirectories" in {
    val dir = JFiles.createTempDirectory("receipts")
    JFiles.write(dir.resolve("invoice.pdf"), Array[Byte](1, 2, 3))
    JFiles.write(dir.resolve("photo.JPG"), Array[Byte](4, 5))
    JFiles.write(dir.resolve("notes.txt"), Array[Byte](9))
    JFiles.createDirectory(dir.resolve("sub"))

    algebra
      .list(dir)
      .map(_.map(file => filename(file.ref.path) -> file.sizeBytes).toSet shouldBe Set(
        "invoice.pdf" -> 3L,
        "photo.JPG" -> 2L
      ))
      .unsafeRunSync()
  }

  "load reads the raw file bytes" in {
    val dir = JFiles.createTempDirectory("receipts")
    val file = JFiles.write(dir.resolve("invoice.pdf"), Array[Byte](7, 8, 9))

    algebra.load(ReceiptRef(file.toString)).map(_.value shouldBe ByteVector(7, 8, 9)).unsafeRunSync()
  }

  "list maps a missing folder to CliError.FileAccess" in {
    algebra
      .list(Path.of("/no/such/receipts/folder"))
      .attempt
      .map {
        case Left(_: CliError.FileAccess) => succeed
        case other => fail(s"expected CliError.FileAccess, got: $other")
      }
      .unsafeRunSync()
  }

  "load maps a missing file to CliError.FileAccess" in {
    algebra
      .load(ReceiptRef("/no/such/receipt.pdf"))
      .attempt
      .map {
        case Left(_: CliError.FileAccess) => succeed
        case other => fail(s"expected CliError.FileAccess, got: $other")
      }
      .unsafeRunSync()
  }
end FileReceiptSourceTest
