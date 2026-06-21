package superfaktura.expense

import superfaktura.receipt.ReceiptBytes

case class ExpensePatch(attachment: Option[ReceiptBytes], comment: Option[String])
