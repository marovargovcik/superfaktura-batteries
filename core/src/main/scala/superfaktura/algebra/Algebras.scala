package superfaktura.algebra

import superfaktura.domain.*

import java.nio.file.Path

trait BankStatementSourceAlgebra[F[_]]:
  def read(path: Path): F[List[Transaction]]

trait ReceiptSourceAlgebra[F[_]]:
  def list(folder: Path): F[List[ReceiptFile]]
  def load(ref: ReceiptRef): F[ReceiptBytes]

trait SuperfakturaAlgebra[F[_]]:
  def listExpenses(window: DateWindow): F[List[Expense]]
  def addExpense(request: NewExpense): F[ExpenseId]
  def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit]

trait PlanStore[F[_]]:
  def save(plan: Plan): F[Unit]
  def load: F[Plan]

trait ReporterAlgebra[F[_]]:
  def summary(plan: Plan): F[Unit]
