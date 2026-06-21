package superfaktura.receipt

trait OcrAlgebra[F[_]]:
  def read(receipt: ReceiptBytes, media: ReceiptMedia): F[OcrResult]
