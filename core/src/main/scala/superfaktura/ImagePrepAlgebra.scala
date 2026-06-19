package superfaktura

trait ImagePrepAlgebra[F[_]]:
  def fit(attachment: ReceiptBytes, format: AttachmentFormat): F[PreparedAttachment]
