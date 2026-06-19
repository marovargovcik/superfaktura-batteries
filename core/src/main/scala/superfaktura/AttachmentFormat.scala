package superfaktura

// The receipt/invoice formats Superfaktura accepts as attachments. Only raster images can be
// downscaled to fit the size cap; PDFs and HEICs that are too large are flagged instead.
enum AttachmentFormat:
  case Jpeg, Png, Pdf, Heic

  def downscalable: Boolean = this match
    case Jpeg | Png => true
    case Pdf | Heic => false

  // The OCR model reads a narrower set than Superfaktura stores — HEIC has no OCR projection.
  def ocrMedia: Option[ReceiptMedia] = this match
    case Jpeg => Some(ReceiptMedia.Jpeg)
    case Png => Some(ReceiptMedia.Png)
    case Pdf => Some(ReceiptMedia.Pdf)
    case Heic => None

object AttachmentFormat:
  def fromExtension(extension: String): Option[AttachmentFormat] =
    extension.toLowerCase match
      case "jpg" | "jpeg" => Some(Jpeg)
      case "png" => Some(Png)
      case "pdf" => Some(Pdf)
      case "heic" => Some(Heic)
      case _ => None
