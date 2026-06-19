package superfaktura

trait SuperfakturaAlgebraStub[F[_]] extends SuperfakturaAlgebra[F]:
  override def listExpenses(window: DateWindow): F[List[Expense]] = ???
  override def addExpense(request: NewExpense, attachment: Option[ReceiptBytes]): F[ExpenseId] = ???
  override def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit] = ???
