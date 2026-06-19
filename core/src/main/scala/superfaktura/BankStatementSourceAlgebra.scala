package superfaktura

import java.nio.file.Path

trait BankStatementSourceAlgebra[F[_]]:
  def read(path: Path): F[List[Transaction]]
