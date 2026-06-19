package superfaktura

import java.time.LocalDate

case class Transaction(
    date: LocalDate,
    amount: Money,
    direction: TransactionType,
    variableSymbol: Option[String],
    description: String
)
