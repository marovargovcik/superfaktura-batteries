package superfaktura

// The media types the vision model accepts, as sent on the wire. Narrower than AttachmentFormat:
// HEIC is absent because the model's image blocks reject it (derive one via AttachmentFormat.ocrMedia).
enum ReceiptMedia(val mimeType: String):
  case Jpeg extends ReceiptMedia("image/jpeg")
  case Png extends ReceiptMedia("image/png")
  case Pdf extends ReceiptMedia("application/pdf")
