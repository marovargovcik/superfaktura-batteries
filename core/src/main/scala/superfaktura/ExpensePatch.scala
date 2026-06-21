package superfaktura

case class ExpensePatch(attachment: Option[ReceiptBytes], comment: Option[String])
