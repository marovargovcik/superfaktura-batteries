package superfaktura.receipt

enum PreparedAttachment:
  case Fitted(bytes: ReceiptBytes)
  case TooLarge(reason: String)
