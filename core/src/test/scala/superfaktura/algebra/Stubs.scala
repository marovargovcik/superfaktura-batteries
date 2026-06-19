package superfaktura.algebra

import superfaktura.domain.*

import java.nio.file.Path

trait BankStatementSourceAlgebraStub[F[_]] extends BankStatementSourceAlgebra[F]:
  override def read(path: Path): F[List[Transaction]] = ???

trait ReceiptSourceAlgebraStub[F[_]] extends ReceiptSourceAlgebra[F]:
  override def list(folder: Path): F[List[ReceiptFile]] = ???
  override def load(ref: ReceiptRef): F[ReceiptBytes] = ???

trait SuperfakturaAlgebraStub[F[_]] extends SuperfakturaAlgebra[F]:
  override def listExpenses(window: DateWindow): F[List[Expense]] = ???
  override def addExpense(request: NewExpense): F[ExpenseId] = ???
  override def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit] = ???

trait PlanStoreStub[F[_]] extends PlanStore[F]:
  override def save(plan: Plan): F[Unit] = ???
  override def load: F[Plan] = ???

trait ReporterAlgebraStub[F[_]] extends ReporterAlgebra[F]:
  override def summary(plan: Plan): F[Unit] = ???
