package superfaktura

import java.time.LocalDate

case class Expense(id: ExpenseId, name: String, amount: Money, created: LocalDate)
