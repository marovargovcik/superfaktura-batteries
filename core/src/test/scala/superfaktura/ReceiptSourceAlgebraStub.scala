package superfaktura

import java.nio.file.Path

trait ReceiptSourceAlgebraStub[F[_]] extends ReceiptSourceAlgebra[F]:
  override def list(folder: Path): F[List[ReceiptFile]] = ???
  override def load(ref: ReceiptRef): F[ReceiptBytes] = ???
