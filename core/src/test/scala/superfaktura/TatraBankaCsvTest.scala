package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class TatraBankaCsvTest extends AnyFreeSpec with Matchers:

  private def row(overrides: (String, String)*): Map[String, String] =
    Map(
      "Dátum spracovania" -> "19.06.2026",
      "Suma"              -> "45,45",
      "Mena"              -> "EUR",
      "Typ"               -> "Debet"
    ) ++ overrides

  "parseRow" - {
    "parses a card payment, keeping recipient info and description" in {
      val info = "423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"

      TatraBankaCsv.parseRow(
        row("Suma" -> "73,71", "Informácia pre príjemcu" -> info, "Popis" -> "GP NÁKUP POS")
      ) shouldBe Right(
        Transaction(
          date = LocalDate.of(2026, 6, 19),
          amount = Money(BigDecimal("73.71"), "EUR"),
          direction = TransactionType.Debit,
          counterpartyIban = None,
          variableSymbol = None,
          specificSymbol = None,
          constantSymbol = None,
          recipientInfo = Some(info),
          description = "GP NÁKUP POS"
        )
      )
    }

    "parses a supplier transfer with IBAN and symbols" in {
      val result = TatraBankaCsv.parseRow(
        row(
          "IBAN"              -> "SK5281800000007000747747",
          "Variabilný symbol" -> "5646196800",
          "Špecifický symbol" -> "202605",
          "Konštantný symbol" -> "3558",
          "Informácia pre príjemcu" -> "UHRADA POISTNEHO",
          "Popis"             -> "Platba 8180/000000-7000747747"
        )
      )

      result.map(_.counterpartyIban) shouldBe Right(Some("SK5281800000007000747747"))
      result.map(_.variableSymbol) shouldBe Right(Some("5646196800"))
      result.map(_.amount) shouldBe Right(Money(BigDecimal("45.45"), "EUR"))
    }

    "maps Kredit to Credit" in {
      TatraBankaCsv.parseRow(row("Typ" -> "Kredit")).map(_.direction) shouldBe Right(TransactionType.Credit)
    }

    "rejects a malformed amount, date, or type, and a missing required column" in {
      TatraBankaCsv.parseRow(row("Suma" -> "n/a")).isLeft shouldBe true
      TatraBankaCsv.parseRow(row("Dátum spracovania" -> "2026/06/19")).isLeft shouldBe true
      TatraBankaCsv.parseRow(row("Typ" -> "Refund")).isLeft shouldBe true
      TatraBankaCsv.parseRow(row() - "Suma").isLeft shouldBe true
    }
  }
