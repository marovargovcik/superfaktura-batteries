package superfaktura.expense

import java.time.LocalDate

import superfaktura.Money

case class NewExpense(
    name: String,
    amount: Money,
    created: LocalDate,
    variableSymbol: Option[String],
    comment: Option[String]
)
