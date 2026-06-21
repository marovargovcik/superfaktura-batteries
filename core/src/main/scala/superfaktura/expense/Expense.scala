package superfaktura.expense

import superfaktura.Money

import java.time.LocalDate

case class Expense(id: ExpenseId, name: String, amount: Money, created: LocalDate, comment: Option[String])
