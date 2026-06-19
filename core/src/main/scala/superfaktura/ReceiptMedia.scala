package superfaktura

// The receipt formats the vision model can read. HEIC is deliberately absent: the model's image
// blocks don't accept it (and the JVM can't transcode it), so HEIC receipts are flagged, not OCR'd.
enum ReceiptMedia(val mimeType: String):
  case Jpeg extends ReceiptMedia("image/jpeg")
  case Png extends ReceiptMedia("image/png")
  case Pdf extends ReceiptMedia("application/pdf")

object ReceiptMedia:
  def fromExtension(extension: String): Option[ReceiptMedia] =
    extension.toLowerCase match
      case "jpg" | "jpeg" => Some(Jpeg)
      case "png" => Some(Png)
      case "pdf" => Some(Pdf)
      case _ => None
