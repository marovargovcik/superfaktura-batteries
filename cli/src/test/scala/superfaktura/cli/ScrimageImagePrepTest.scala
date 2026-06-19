package superfaktura.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.{AttachmentFormat, PreparedAttachment, ReceiptBytes}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ScrimageImagePrepTest extends AnyFreeSpec with Matchers:

  private def noisyPng(width: Int, height: Int): Array[Byte] =
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rng = java.util.Random(42)
    for x <- 0 until width; y <- 0 until height do image.setRGB(x, y, rng.nextInt(0xffffff))
    val out = ByteArrayOutputStream()
    assert(ImageIO.write(image, "png", out))
    out.toByteArray

  "fit" - {
    "passes an attachment already within the limit through unchanged" in {
      val small = ReceiptBytes(ByteVector(1, 2, 3))
      ScrimageImagePrep.fitting[IO](maxBytes = 4_000_000).fit(small, AttachmentFormat.Pdf).unsafeRunSync() shouldBe
        PreparedAttachment.Fitted(small)
    }

    "flags an oversized PDF as too large rather than downscaling it" in {
      val pdf = ReceiptBytes(ByteVector(1, 2, 3, 4))
      ScrimageImagePrep.fitting[IO](maxBytes = 2).fit(pdf, AttachmentFormat.Pdf).unsafeRunSync() match
        case _: PreparedAttachment.TooLarge => succeed
        case other => fail(s"expected TooLarge, got: $other")
    }

    "flags an oversized HEIC as too large" in {
      val heic = ReceiptBytes(ByteVector(1, 2, 3, 4))
      ScrimageImagePrep.fitting[IO](maxBytes = 2).fit(heic, AttachmentFormat.Heic).unsafeRunSync() match
        case _: PreparedAttachment.TooLarge => succeed
        case other => fail(s"expected TooLarge, got: $other")
    }

    "downscales an oversized raster image until it fits the limit" in {
      val original = ReceiptBytes(ByteVector(noisyPng(256, 256)))
      val limit = 50L * 1024
      original.value.size should be > limit
      ScrimageImagePrep.fitting[IO](limit).fit(original, AttachmentFormat.Png).unsafeRunSync() match
        case PreparedAttachment.Fitted(fitted) => fitted.value.size should be <= limit
        case other => fail(s"expected Fitted, got: $other")
    }

    "flags a raster it cannot shrink under an impossibly small limit" in {
      val original = ReceiptBytes(ByteVector(noisyPng(128, 128)))
      ScrimageImagePrep.fitting[IO](maxBytes = 1).fit(original, AttachmentFormat.Png).unsafeRunSync() match
        case _: PreparedAttachment.TooLarge => succeed
        case other => fail(s"expected TooLarge, got: $other")
    }
  }
end ScrimageImagePrepTest
