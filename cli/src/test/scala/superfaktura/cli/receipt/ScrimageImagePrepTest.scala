package superfaktura.cli.receipt

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sksamuel.scrimage.ImmutableImage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.CliError
import superfaktura.receipt.{AttachmentFormat, PreparedAttachment, ReceiptBytes}

class ScrimageImagePrepTest extends AnyFreeSpec with Matchers:

  private def noisyPng(width: Int, height: Int): Array[Byte] =
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rng = Random(42)
    for x <- 0 until width; y <- 0 until height do image.setRGB(x, y, rng.nextInt(0xffffff))
    val out = ByteArrayOutputStream()
    assert(ImageIO.write(image, "png", out))
    out.toByteArray

  "fit" - {
    "passes an attachment already within the limit through unchanged" in {
      val small = ReceiptBytes(ByteVector(1, 2, 3))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 4_000_000).fit(small, AttachmentFormat.Pdf)
        yield result shouldBe PreparedAttachment.Fitted(small)
      test.unsafeRunSync()
    }

    "treats an attachment exactly at the limit as fitting" in {
      val exact = ReceiptBytes(ByteVector(1, 2, 3))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 3).fit(exact, AttachmentFormat.Png)
        yield result shouldBe PreparedAttachment.Fitted(exact)
      test.unsafeRunSync()
    }

    "maps an undecodable image to CliError.ImageInvalid" in {
      val garbage = ReceiptBytes(ByteVector("this is not an image".getBytes))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 2).fit(garbage, AttachmentFormat.Jpeg).attempt
        yield result match
          case Left(_: CliError.ImageInvalid) => succeed
          case other => fail(s"expected CliError.ImageInvalid, got: $other")
      test.unsafeRunSync()
    }

    "flags an oversized PDF as too large rather than downscaling it" in {
      val pdf = ReceiptBytes(ByteVector(1, 2, 3, 4))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 2).fit(pdf, AttachmentFormat.Pdf)
        yield result match
          case _: PreparedAttachment.TooLarge => succeed
          case other => fail(s"expected TooLarge, got: $other")
      test.unsafeRunSync()
    }

    "flags an oversized HEIC as too large" in {
      val heic = ReceiptBytes(ByteVector(1, 2, 3, 4))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 2).fit(heic, AttachmentFormat.Heic)
        yield result match
          case _: PreparedAttachment.TooLarge => succeed
          case other => fail(s"expected TooLarge, got: $other")
      test.unsafeRunSync()
    }

    "downscales an oversized raster image until it fits the limit" in {
      val original = ReceiptBytes(ByteVector(noisyPng(256, 256)))
      val limit = 50L * 1024
      original.value.size should be > limit
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](limit).fit(original, AttachmentFormat.Png)
        yield result match
          case PreparedAttachment.Fitted(fitted) =>
            fitted.value.size should be <= limit
            noException should be thrownBy ImmutableImage.loader().fromBytes(fitted.value.toArray)
          case other => fail(s"expected Fitted, got: $other")
      test.unsafeRunSync()
    }

    "flags a raster it cannot shrink under an impossibly small limit" in {
      val original = ReceiptBytes(ByteVector(noisyPng(128, 128)))
      val test =
        for
          result <- ScrimageImagePrep.fitting[IO](maxBytes = 1).fit(original, AttachmentFormat.Png)
        yield result match
          case _: PreparedAttachment.TooLarge => succeed
          case other => fail(s"expected TooLarge, got: $other")
      test.unsafeRunSync()
    }
  }
end ScrimageImagePrepTest
