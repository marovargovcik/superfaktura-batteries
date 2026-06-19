package superfaktura

import java.time.LocalDate

case class Receipt(ref: ReceiptRef, amount: Money, date: LocalDate)
