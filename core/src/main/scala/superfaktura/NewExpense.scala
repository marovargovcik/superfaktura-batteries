package superfaktura

import java.time.LocalDate

case class NewExpense(
    name: String,
    amount: Money,
    created: LocalDate,
    variableSymbol: Option[String],
    comment: Option[String]
)
