package superfaktura.bank

import java.time.LocalDate

import superfaktura.Money

case class Transaction(
    date: LocalDate,
    amount: Money,
    direction: TransactionType,
    counterpartyIban: Option[String],
    variableSymbol: Option[String],
    specificSymbol: Option[String],
    recipientInfo: Option[String],
    description: String
)
