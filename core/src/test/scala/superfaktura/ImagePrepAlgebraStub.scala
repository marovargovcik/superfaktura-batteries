package superfaktura

trait ImagePrepAlgebraStub[F[_]] extends ImagePrepAlgebra[F]:
  override def fit(attachment: ReceiptBytes, format: AttachmentFormat): F[PreparedAttachment] = ???
