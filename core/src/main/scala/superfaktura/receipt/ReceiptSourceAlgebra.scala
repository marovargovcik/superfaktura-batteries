package superfaktura.receipt

import java.nio.file.Path

trait ReceiptSourceAlgebra[F[_]]:
  def list(folder: Path): F[List[ReceiptFile]]
  def load(ref: ReceiptRef): F[ReceiptBytes]
