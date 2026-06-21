package superfaktura.bank

import superfaktura.Money

import java.time.LocalDate

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
