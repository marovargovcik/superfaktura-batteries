package superfaktura.domain

import scodec.bits.ByteVector

import java.time.LocalDate

case class Money(amount: BigDecimal, currency: String)

enum TransactionType:
  case Debit, Credit

case class Transaction(
    date: LocalDate,
    amount: Money,
    direction: TransactionType,
    variableSymbol: Option[String],
    description: String
)

case class ExpenseId(value: Long)

case class Expense(id: ExpenseId, name: String, amount: Money, created: LocalDate)

case class NewExpense(
    name: String,
    amount: Money,
    created: LocalDate,
    variableSymbol: Option[String],
    comment: Option[String]
)

case class ExpensePatch(attachment: Option[ReceiptBytes])

case class ReceiptRef(path: String)

case class ReceiptFile(ref: ReceiptRef, sizeBytes: Long)

case class ReceiptBytes(value: ByteVector)

case class ExternalRef(value: String)

case class CandidateExpense(externalRef: ExternalRef, name: String, amount: Money, occurredOn: LocalDate)

case class DateWindow(from: LocalDate, to: LocalDate)
