package superfaktura.receipt

trait OcrAlgebraStub[F[_]] extends OcrAlgebra[F]:
  override def read(receipt: ReceiptBytes, media: ReceiptMedia): F[OcrResult] = ???
