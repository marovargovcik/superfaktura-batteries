package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ReceiptMediaTest extends AnyFreeSpec with Matchers:

  "fromExtension maps supported types case-insensitively, and rejects HEIC and unknowns" in {
    ReceiptMedia.fromExtension("JPG") shouldBe Some(ReceiptMedia.Jpeg)
    ReceiptMedia.fromExtension("jpeg") shouldBe Some(ReceiptMedia.Jpeg)
    ReceiptMedia.fromExtension("png") shouldBe Some(ReceiptMedia.Png)
    ReceiptMedia.fromExtension("pdf") shouldBe Some(ReceiptMedia.Pdf)
    ReceiptMedia.fromExtension("heic") shouldBe None
    ReceiptMedia.fromExtension("txt") shouldBe None
  }
end ReceiptMediaTest
