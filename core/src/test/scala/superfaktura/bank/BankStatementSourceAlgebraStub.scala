package superfaktura.bank

import java.nio.file.Path

trait BankStatementSourceAlgebraStub[F[_]] extends BankStatementSourceAlgebra[F]:
  override def read(path: Path): F[List[Transaction]] = ???
