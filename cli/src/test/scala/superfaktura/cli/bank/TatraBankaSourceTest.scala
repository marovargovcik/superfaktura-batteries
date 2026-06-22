package superfaktura.cli.bank

import java.nio.charset.Charset
import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.{CliError, Money}
import superfaktura.bank.TransactionType

class TatraBankaSourceTest extends AnyFreeSpec with Matchers:

  private val windows1250 = Charset.forName("windows-1250")

  private val sample: Path =
    val tmp = Files.createTempFile("tatra-sample", ".csv")
    Files.write(tmp, getClass.getResourceAsStream("/tatra-sample.csv").readAllBytes())
    tmp

  "read" - {
    "decodes the Windows-1250 export and parses every row" in {
      val test =
        for
          transactions <- TatraBankaSource.live[IO].read(sample)
        yield
          transactions.map(_.direction) shouldBe List(
            TransactionType.Debit,
            TransactionType.Debit,
            TransactionType.Credit
          )
          transactions.head.variableSymbol shouldBe Some("5646196800")
          transactions.head.counterpartyIban shouldBe Some("SK5281800000007000747747")
          transactions(1).recipientInfo.exists(_.contains("VARGOVČÍK")) shouldBe true
          transactions(1).amount shouldBe Money(BigDecimal("73.71"), "EUR")
      test.unsafeRunSync()
    }

    "fails with CsvInvalid on a malformed row" in {
      val bad = Files.createTempFile("tatra-bad", ".csv")
      val content = "Dátum spracovania,Suma,Mena,Typ\n19.06.2026,n/a,EUR,Debet\n"
      Files.write(bad, content.getBytes(windows1250))

      val test =
        for
          result <- TatraBankaSource.live[IO].read(bad).attempt
        yield result match
          case Left(_: CliError.CsvInvalid) => succeed
          case other => fail(s"expected CsvInvalid, got: $other")
      test.unsafeRunSync()
    }
  }
end TatraBankaSourceTest
