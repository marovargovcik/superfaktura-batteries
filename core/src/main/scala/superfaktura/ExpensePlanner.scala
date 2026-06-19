package superfaktura

import superfaktura.domain.*

object ExpensePlanner:

  def toCandidates(transactions: List[Transaction]): List[CandidateExpense] =
    transactions.collect:
      case t if t.direction == TransactionType.Debit =>
        CandidateExpense(
          externalRef = externalRef(t),
          name = t.description,
          amount = t.amount,
          occurredOn = t.date
        )

  private def externalRef(t: Transaction): ExternalRef =
    ExternalRef(
      s"${t.date}|${t.amount.amount}|${t.amount.currency}|${t.variableSymbol.getOrElse("")}|${t.description}"
    )
