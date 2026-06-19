package superfaktura

trait OcrAlgebra[F[_]]:
  def read(receipt: ReceiptBytes, media: ReceiptMedia): F[OcrResult]
