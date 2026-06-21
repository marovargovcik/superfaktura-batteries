package superfaktura

// Superfaktura discards the original filename of an attachment, so the comment carries a content-hash
// token (`sfrcpt:<sha256>`) of each uploaded receipt — letting a re-run recognise it without re-OCRing.
case class ReceiptMarker(value: String)

object ReceiptMarker:
  private val prefix = "sfrcpt:"

  def of(hash: String): ReceiptMarker = ReceiptMarker(s"$prefix$hash")

  def parse(token: String): Option[ReceiptMarker] = Option.when(token.startsWith(prefix))(ReceiptMarker(token))
