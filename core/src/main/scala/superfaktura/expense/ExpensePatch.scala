package superfaktura.expense

import superfaktura.receipt.ReceiptBytes

case class ExpensePatch(name: Option[String], attachment: Option[ReceiptBytes], comment: Option[String])
