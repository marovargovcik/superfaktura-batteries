package superfaktura

import superfaktura.domain.*

object ExpensePlanner:

  def toCandidates(transactions: List[Transaction]): List[CandidateExpense] =
    transactions.collect:
      case transaction if transaction.direction == TransactionType.Debit =>
        CandidateExpense(
          externalRef = externalRef(transaction),
          name = transaction.description,
          amount = transaction.amount,
          occurredOn = transaction.date
        )

  private def externalRef(transaction: Transaction): ExternalRef =
    ExternalRef(
      List(
        transaction.date,
        transaction.amount.amount,
        transaction.amount.currency,
        transaction.variableSymbol.getOrElse(""),
        transaction.description
      ).mkString("|")
    )
