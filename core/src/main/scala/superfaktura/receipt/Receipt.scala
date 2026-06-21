package superfaktura.receipt

import superfaktura.Money

import java.time.LocalDate

case class Receipt(ref: ReceiptRef, amount: Money, date: LocalDate)
