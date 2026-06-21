package superfaktura.expense

import java.time.LocalDate

import superfaktura.Money

case class Expense(id: ExpenseId, name: String, amount: Money, created: LocalDate, comment: Option[String])
