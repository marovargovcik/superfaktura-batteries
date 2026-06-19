package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AttachmentFormatTest extends AnyFreeSpec with Matchers:

  "fromExtension maps the supported formats case-insensitively and rejects unknowns" in {
    AttachmentFormat.fromExtension("JPG") shouldBe Some(AttachmentFormat.Jpeg)
    AttachmentFormat.fromExtension("jpeg") shouldBe Some(AttachmentFormat.Jpeg)
    AttachmentFormat.fromExtension("png") shouldBe Some(AttachmentFormat.Png)
    AttachmentFormat.fromExtension("pdf") shouldBe Some(AttachmentFormat.Pdf)
    AttachmentFormat.fromExtension("heic") shouldBe Some(AttachmentFormat.Heic)
    AttachmentFormat.fromExtension("txt") shouldBe None
  }

  "downscalable is true only for raster images" in {
    AttachmentFormat.Jpeg.downscalable shouldBe true
    AttachmentFormat.Png.downscalable shouldBe true
    AttachmentFormat.Pdf.downscalable shouldBe false
    AttachmentFormat.Heic.downscalable shouldBe false
  }

  "ocrMedia projects to the OCR subset, with HEIC having none" in {
    AttachmentFormat.Jpeg.ocrMedia shouldBe Some(ReceiptMedia.Jpeg)
    AttachmentFormat.Png.ocrMedia shouldBe Some(ReceiptMedia.Png)
    AttachmentFormat.Pdf.ocrMedia shouldBe Some(ReceiptMedia.Pdf)
    AttachmentFormat.Heic.ocrMedia shouldBe None
  }
end AttachmentFormatTest
