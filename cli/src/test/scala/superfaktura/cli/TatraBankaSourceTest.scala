package superfaktura.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.TransactionType

import java.nio.file.{Files, Path}

class TatraBankaSourceTest extends AnyFreeSpec with Matchers:

  // copy the Windows-1250 fixture out of the classpath onto disk so the interpreter can read it by Path
  private val sample: Path =
    val tmp = Files.createTempFile("tatra-sample", ".csv")
    Files.write(tmp, getClass.getResourceAsStream("/tatra-sample.csv").readAllBytes())
    tmp

  "read" - {
    "decodes the Windows-1250 export and parses every row" in {
      val transactions = TatraBankaSource.live[IO].read(sample).unsafeRunSync()

      transactions.map(_.direction) shouldBe List(
        TransactionType.Debit,
        TransactionType.Debit,
        TransactionType.Credit
      )
      transactions.head.variableSymbol shouldBe Some("5646196800")
      transactions.head.counterpartyIban shouldBe Some("SK5281800000007000747747")
      transactions(1).recipientInfo.exists(_.contains("SHELL 8203")) shouldBe true
      transactions(1).amount shouldBe superfaktura.Money(BigDecimal("73.71"), "EUR")
    }
  }
