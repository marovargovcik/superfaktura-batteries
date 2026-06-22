package superfaktura.cli.receipt

import java.time.LocalDate

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.*
import scodec.bits.ByteVector
import superfaktura.{CliError, Money}
import superfaktura.cli.{ClaudeConfig, Secret}
import superfaktura.receipt.{OcrAlgebra, OcrResult, ReceiptBytes, ReceiptMedia}

class ClaudeOcrTest extends AnyFreeSpec with Matchers:

  private given ClaudeConfig = ClaudeConfig(
    apiUrl = "https://api.anthropic.com",
    apiKey = Secret("sk-test"),
    model = "claude-haiku-4-5-20251001",
    maxTokens = 1024
  )

  private val bytes = ReceiptBytes(ByteVector(1, 2, 3))

  private def clientOf(app: HttpApp[IO]): Client[IO] = Client.fromHttpApp(app)

  private def respond(body: String, status: Status = Status.Ok): Client[IO] =
    clientOf(HttpApp[IO](_ => Response[IO](status).withEntity(parse(body).toOption.get).pure[IO]))

  private def capturing(captured: Ref[IO, Json], responseBody: String): Client[IO] =
    clientOf(HttpApp[IO]: request =>
      request.as[Json].flatMap(captured.set) *>
        Response[IO](Status.Ok).withEntity(parse(responseBody).toOption.get).pure[IO])

  private def algebra(client: Client[IO]): OcrAlgebra[IO] =
    given Client[IO] = client
    ClaudeOcr.live[IO]

  private def toolUse(input: String): String =
    s"""{"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t1","name":"record_receipt","input":$input}]}"""

  "read" - {
    "extracts the amount, currency, and date from the tool_use block" in {
      val client = respond(toolUse("""{"amount":73.71,"currency":"EUR","date":"2026-06-13"}"""))
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .map(_ shouldBe OcrResult(Some(Money(BigDecimal("73.71"), "EUR")), Some(LocalDate.of(2026, 6, 13))))
        .unsafeRunSync()
    }

    "leaves the date empty when the model omits it" in {
      val client = respond(toolUse("""{"amount":10.00,"currency":"EUR"}"""))
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .map(_ shouldBe OcrResult(Some(Money(BigDecimal("10.00"), "EUR")), None))
        .unsafeRunSync()
    }

    "drops the amount when the currency is missing (an amount we can't denominate is not a Money)" in {
      val client = respond(toolUse("""{"amount":10.00,"date":"2026-06-13"}"""))
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .map(_ shouldBe OcrResult(None, Some(LocalDate.of(2026, 6, 13))))
        .unsafeRunSync()
    }

    "treats an unparseable date as absent rather than failing" in {
      val client = respond(toolUse("""{"amount":1.00,"currency":"EUR","date":"2026-13-40"}"""))
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .map(_ shouldBe OcrResult(Some(Money(BigDecimal("1.00"), "EUR")), None))
        .unsafeRunSync()
    }

    "raises CliError.Decode when the response has no tool_use block" in {
      val client = respond("""{"content":[{"type":"text","text":"sorry"}]}""")
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .attempt
        .map {
          case Left(_: CliError.Decode) => succeed
          case other => fail(s"expected CliError.Decode, got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.Decode when content is not an array" in {
      val client = respond("""{"content":"oops"}""")
      algebra(client)
        .read(bytes, ReceiptMedia.Jpeg)
        .attempt
        .map {
          case Left(_: CliError.Decode) => succeed
          case other => fail(s"expected CliError.Decode, got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.Api carrying the Anthropic error message on a non-2xx response" in {
      val body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
      algebra(respond(body, Status.ServiceUnavailable))
        .read(bytes, ReceiptMedia.Jpeg)
        .attempt
        .map {
          case Left(CliError.Api(503, message)) => message should include("Overloaded")
          case other => fail(s"expected Api(503), got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.ConfigInvalid when apiUrl is not https" in {
      given ClaudeConfig =
        ClaudeConfig(apiUrl = "http://insecure.example", apiKey = Secret("k"), model = "m", maxTokens = 256)
      given Client[IO] = respond(toolUse("""{"amount":1.0,"currency":"EUR","date":"2026-06-13"}"""))
      ClaudeOcr
        .live[IO]
        .read(bytes, ReceiptMedia.Jpeg)
        .attempt
        .map {
          case Left(_: CliError.ConfigInvalid) => succeed
          case other => fail(s"expected CliError.ConfigInvalid, got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.ConfigInvalid when the API key is empty, without calling the API" in {
      given ClaudeConfig =
        ClaudeConfig(apiUrl = "https://api.anthropic.com", apiKey = Secret(""), model = "m", maxTokens = 256)
      given Client[IO] = clientOf(HttpApp[IO](_ => IO.raiseError(new AssertionError("must not call the API"))))
      ClaudeOcr
        .live[IO]
        .read(bytes, ReceiptMedia.Jpeg)
        .attempt
        .map {
          case Left(_: CliError.ConfigInvalid) => succeed
          case other => fail(s"expected CliError.ConfigInvalid, got: $other")
        }
        .unsafeRunSync()
    }

    "posts a base64 image block and forces the record_receipt tool" in {
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, toolUse("""{"amount":1.0,"currency":"EUR","date":"2026-06-13"}""")))
            .read(bytes, ReceiptMedia.Jpeg)
          json <- captured.get
        yield
          val request = json.hcursor
          request.get[String]("model") shouldBe Right("claude-haiku-4-5-20251001")
          request.downField("tool_choice").get[String]("name") shouldBe Right("record_receipt")
          val source = request.downField("messages").downN(0).downField("content").downN(0)
          source.get[String]("type") shouldBe Right("image")
          source.downField("source").get[String]("media_type") shouldBe Right("image/jpeg")
          source.downField("source").get[String]("data") shouldBe Right("AQID")
      test.unsafeRunSync()
    }

    "sends a PDF as a document block" in {
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, toolUse("""{"amount":1.0,"currency":"EUR","date":"2026-06-13"}""")))
            .read(bytes, ReceiptMedia.Pdf)
          json <- captured.get
        yield
          val source = json.hcursor.downField("messages").downN(0).downField("content").downN(0)
          source.get[String]("type") shouldBe Right("document")
          source.downField("source").get[String]("media_type") shouldBe Right("application/pdf")
      test.unsafeRunSync()
    }

    "sends the x-api-key and anthropic-version headers" in {
      val app = HttpApp[IO]: request =>
        val key = request.headers.get(ci"x-api-key").map(_.head.value)
        val version = request.headers.get(ci"anthropic-version").map(_.head.value)
        if key.contains("sk-test") && version.contains("2023-06-01") then
          Response[IO](Status.Ok)
            .withEntity(parse(toolUse("""{"amount":1.0,"currency":"EUR","date":"2026-06-13"}""")).toOption.get)
            .pure[IO]
        else Response[IO](Status.Forbidden).withEntity(parse("""{"content":[]}""").toOption.get).pure[IO]
      algebra(clientOf(app))
        .read(bytes, ReceiptMedia.Jpeg)
        .map(_.amount shouldBe Some(Money(BigDecimal("1.0"), "EUR")))
        .unsafeRunSync()
    }
  }
end ClaudeOcrTest
