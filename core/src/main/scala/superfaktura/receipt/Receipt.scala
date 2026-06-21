package superfaktura.receipt

import java.time.LocalDate

import superfaktura.Money

case class Receipt(ref: ReceiptRef, amount: Money, date: LocalDate)
