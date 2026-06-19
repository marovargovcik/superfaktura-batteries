package superfaktura

enum PreparedAttachment:
  case Fitted(bytes: ReceiptBytes)
  case TooLarge(reason: String)
