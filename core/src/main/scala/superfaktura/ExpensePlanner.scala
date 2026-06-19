package superfaktura

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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

  // Tatra banka packs card-payment recipient info as
  // "<masked-PAN> <city> <cardholder> <yyyymmdd> <hh:mm:ss> <amount><CCY> <merchant>".
  private val merchantAfterAmount = """\d+\.\d{2}[A-Z]{3}\s+(.+)$""".r

  private def cardMerchant(transaction: Transaction): Option[String] =
    Option
      .when(isCardPayment(transaction))(transaction.recipientInfo)
      .flatten
      .flatMap(info => merchantAfterAmount.findFirstMatchIn(info).map(_.group(1).trim))

  private def isCardPayment(transaction: Transaction): Boolean =
    transaction.description.startsWith("GP NÁKUP POS") || transaction.description.startsWith("INT NÁKUP POS")

  private def externalRef(transaction: Transaction): ExternalRef =
    val fields = List(
      transaction.date.toString,
      transaction.amount.amount.toString,
      transaction.amount.currency,
      transaction.variableSymbol.getOrElse(""),
      transaction.specificSymbol.getOrElse(""),
      transaction.counterpartyIban.getOrElse(""),
      transaction.description
    )
    // length-prefix each field so the joined form is injective regardless of field content, then hash for a stable id
    val canonical = fields.map(field => s"${field.length}:$field").mkString
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
    ExternalRef(digest.map(byte => f"${byte & 0xff}%02x").mkString)
end ExpensePlanner
