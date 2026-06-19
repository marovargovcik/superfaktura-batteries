package superfaktura

object ExpensePlanner:

  def toCandidates(transactions: List[Transaction]): List[CandidateExpense] =
    transactions.collect:
      case transaction if transaction.direction == TransactionType.Debit =>
        CandidateExpense(
          externalRef = externalRef(transaction),
          name = expenseName(transaction),
          amount = transaction.amount,
          occurredOn = transaction.date
        )

  private def expenseName(transaction: Transaction): String =
    cardMerchant(transaction)
      .orElse(transaction.recipientInfo)
      .getOrElse(transaction.description)

  // Card-payment recipient info is "<masked-PAN> <city> <cardholder> <yyyymmdd> <hh:mm:ss> <amount><CCY> <merchant>";
  // the merchant (e.g. "SHELL 8203", "CLAUDE.AI SUBSCRIPTION") is whatever follows the amount+currency token.
  private val merchantAfterAmount = """\d+\.\d{2}[A-Z]{3}\s+(.+)$""".r

  private def cardMerchant(transaction: Transaction): Option[String] =
    transaction.recipientInfo.flatMap(info => merchantAfterAmount.findFirstMatchIn(info).map(_.group(1).trim))

  private def externalRef(transaction: Transaction): ExternalRef =
    ExternalRef(
      List(
        transaction.date,
        transaction.amount.amount,
        transaction.amount.currency,
        transaction.variableSymbol.getOrElse(""),
        transaction.specificSymbol.getOrElse(""),
        transaction.counterpartyIban.getOrElse(""),
        transaction.description
      ).mkString("|")
    )
end ExpensePlanner
