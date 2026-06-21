package superfaktura.bank

import superfaktura.Money

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.Try

object TatraBankaCsv:

  // Column headers as they appear in the Windows-1250-decoded Tatra banka export.
  private val ProcessedOn = "Dátum spracovania"
  private val Amount = "Suma"
  private val Currency = "Mena"
  private val Type = "Typ"
  private val Iban = "IBAN"
  private val VariableSymbol = "Variabilný symbol"
  private val SpecificSymbol = "Špecifický symbol"
  private val RecipientInfo = "Informácia pre príjemcu"
  private val Description = "Popis"

  private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

  def parseRow(row: Map[String, String]): Either[String, Transaction] =
    for
      rawDate <- field(row, ProcessedOn)
      date <- parseDate(rawDate)
      rawAmount <- field(row, Amount)
      amount <- parseAmount(rawAmount)
      rawType <- field(row, Type)
      direction <- parseDirection(rawType)
      currency <- field(row, Currency)
    yield Transaction(
      date = date,
      amount = Money(amount, currency),
      direction = direction,
      counterpartyIban = optional(row, Iban),
      variableSymbol = optional(row, VariableSymbol),
      specificSymbol = optional(row, SpecificSymbol),
      recipientInfo = optional(row, RecipientInfo),
      description = row.getOrElse(Description, "")
    )

  private def field(row: Map[String, String], key: String): Either[String, String] =
    row.get(key).toRight(s"missing column: '$key'")

  private def optional(row: Map[String, String], key: String): Option[String] =
    row.get(key).map(_.trim).filter(_.nonEmpty)

  private def parseDate(raw: String): Either[String, LocalDate] =
    Try(LocalDate.parse(raw.trim, dateFormat)).toEither.left.map(_ => s"invalid date: '$raw'")

  private def parseAmount(raw: String): Either[String, BigDecimal] =
    Try(BigDecimal(raw.trim.replace(" ", "").replace(",", "."))).toEither.left.map(_ => s"invalid amount: '$raw'")

  private def parseDirection(raw: String): Either[String, TransactionType] =
    raw.trim match
      case "Debet" => Right(TransactionType.Debit)
      case "Kredit" => Right(TransactionType.Credit)
      case other => Left(s"unknown transaction type: '$other'")
end TatraBankaCsv
