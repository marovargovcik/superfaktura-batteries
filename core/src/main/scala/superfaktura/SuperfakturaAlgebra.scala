package superfaktura

trait SuperfakturaAlgebra[F[_]]:
  def listExpenses(window: DateWindow): F[List[Expense]]
  def addExpense(request: NewExpense): F[ExpenseId]
  def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit]
