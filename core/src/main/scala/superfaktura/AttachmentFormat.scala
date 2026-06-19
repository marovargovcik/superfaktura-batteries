package superfaktura

// The receipt/invoice formats Superfaktura accepts as attachments.
enum AttachmentFormat:
  case Jpeg, Png, Pdf, Heic

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
