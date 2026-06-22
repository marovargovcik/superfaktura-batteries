package superfaktura.cli.expense

import java.time.LocalDate
import java.util.Base64

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
import superfaktura.{CliError, DateWindow, Money}
import superfaktura.cli.{Secret, SuperfakturaConfig}
import superfaktura.expense.{Expense, ExpenseId, ExpensePatch, NewExpense, SuperfakturaAlgebra}
import superfaktura.receipt.ReceiptBytes

class SuperfakturaClientTest extends AnyFreeSpec with Matchers:

  private given SuperfakturaConfig = SuperfakturaConfig(
    apiUrl = "https://sandbox.superfaktura.sk",
    email = "a+b@example.com",
    apiKey = Secret("key"),
    companyId = "119297",
    module = "test 1.0"
  )

  private val window = DateWindow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

  private def newExpense = NewExpense(
    name = "SHELL",
    amount = Money(BigDecimal("73.71"), "EUR"),
    created = LocalDate.of(2026, 6, 16),
    variableSymbol = None,
    comment = None
  )

  private def clientOf(app: HttpApp[IO]): Client[IO] = Client.fromHttpApp(app)

  private def respond(body: String, status: Status = Status.Ok): Client[IO] =
    clientOf(HttpApp[IO](_ => Response[IO](status).withEntity(parse(body).toOption.get).pure[IO]))

  private def capturing(captured: Ref[IO, Json], responseBody: String): Client[IO] =
    clientOf(HttpApp[IO]: request =>
      request.as[Json].flatMap(captured.set) *>
        Response[IO](Status.Ok).withEntity(parse(responseBody).toOption.get).pure[IO])

  private def algebra(client: Client[IO]): SuperfakturaAlgebra[IO] =
    given Client[IO] = client
    SuperfakturaClient.live[IO]

  "addExpense" - {
    "posts the expense and returns the new id" in {
      algebra(respond("""{"data":{"Expense":{"id":123}},"error":0}"""))
        .addExpense(newExpense, None)
        .map(_ shouldBe ExpenseId(123))
        .unsafeRunSync()
    }

    "posts the documented expense body (gross amount, vat 0, invoice, already paid)" in {
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, """{"data":{"Expense":{"id":1}},"error":0}""")).addExpense(newExpense, None)
          json <- captured.get
        yield
          val expense = json.hcursor.downField("Expense")
          expense.get[BigDecimal]("amount") shouldBe Right(BigDecimal("73.71"))
          expense.get[Int]("vat") shouldBe Right(0)
          expense.get[String]("currency") shouldBe Right("EUR")
          expense.get[String]("type") shouldBe Right("invoice")
          expense.get[Int]("already_paid") shouldBe Right(1)
      test.unsafeRunSync()
    }

    "carries a base64 attachment in the create body when given one" in {
      val attachment = Some(ReceiptBytes(ByteVector("hi".getBytes)))
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, """{"data":{"Expense":{"id":1}},"error":0}"""))
            .addExpense(newExpense, attachment)
          json <- captured.get
        yield json.hcursor.downField("Expense").get[String]("attachment") shouldBe
          Right(Base64.getEncoder.encodeToString("hi".getBytes))
      test.unsafeRunSync()
    }

    "raises CliError.Api when the body reports an error" in {
      algebra(respond("""{"error":1,"error_message":"Chýbajúce údaje"}"""))
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(_: CliError.Api) => succeed
          case other => fail(s"expected CliError.Api, got: $other")
        }
        .unsafeRunSync()
    }

    "surfaces an object-shaped error_message" in {
      val client = respond("""{"error":1,"error_message":{"number":["Číslo dokladu je povinné"]}}""")
      algebra(client)
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(error: CliError.Api) => error.getMessage should include("Číslo dokladu")
          case other => fail(s"expected CliError.Api, got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.Api on a non-2xx response with no error field" in {
      algebra(respond("{}", Status.InternalServerError))
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(CliError.Api(500, _)) => succeed
          case other => fail(s"expected Api(500), got: $other")
        }
        .unsafeRunSync()
    }

    "raises CliError.Decode on a non-JSON body" in {
      val client = clientOf(HttpApp[IO](_ => Response[IO](Status.Ok).withEntity("not json").pure[IO]))
      algebra(client)
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(_: CliError.Decode) => succeed
          case other => fail(s"expected CliError.Decode, got: $other")
        }
        .unsafeRunSync()
    }

    "rejects a non-https apiUrl rather than sending the key over plaintext" in {
      given SuperfakturaConfig = SuperfakturaConfig(
        apiUrl = "http://insecure.example",
        email = "e@example.com",
        apiKey = Secret("key"),
        companyId = "1",
        module = "m"
      )
      given Client[IO] = respond("""{"data":{"Expense":{"id":1}},"error":0}""")
      SuperfakturaClient
        .live[IO]
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(_: CliError.ConfigInvalid) => succeed
          case other => fail(s"expected CliError.ConfigInvalid, got: $other")
        }
        .unsafeRunSync()
    }

    "falls back to 'unknown error' when an error body carries no error_message" in {
      algebra(respond("""{"error":1}"""))
        .addExpense(newExpense, None)
        .attempt
        .map {
          case Left(CliError.Api(_, body)) => body shouldBe "unknown error"
          case other => fail(s"expected CliError.Api, got: $other")
        }
        .unsafeRunSync()
    }

    "sends a URL-encoded SFAPI Authorization header" in {
      val app = HttpApp[IO]: request =>
        val auth = request.headers.get(ci"Authorization").map(_.head.value).getOrElse("")
        if auth.startsWith("SFAPI email=a%2Bb%40example.com&apikey=key") then
          Response[IO](Status.Ok).withEntity(parse("""{"data":{"Expense":{"id":1}},"error":0}""").toOption.get).pure[IO]
        else
          Response[IO](Status.Forbidden).withEntity(parse("""{"error":1,"error_message":"no auth"}""").toOption.get)
            .pure[IO]
      algebra(clientOf(app)).addExpense(newExpense, None).map(_ shouldBe ExpenseId(1)).unsafeRunSync()
    }
  }

  "editExpense" - {
    "posts the id, the comment, and the base64-encoded attachment" in {
      val patch = ExpensePatch(None, Some(ReceiptBytes(ByteVector("hi".getBytes))), Some("sfref:r sfrcpt:abc"))
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, """{"error":0}""")).editExpense(ExpenseId(42), patch)
          json <- captured.get
        yield
          val expense = json.hcursor.downField("Expense")
          expense.get[Long]("id") shouldBe Right(42)
          expense.get[String]("comment") shouldBe Right("sfref:r sfrcpt:abc")
          expense.get[String]("attachment") shouldBe Right(Base64.getEncoder.encodeToString("hi".getBytes))
          expense.downField("name").succeeded shouldBe false
      test.unsafeRunSync()
    }

    "sends only the new name when renaming, leaving attachment and comment untouched" in {
      val patch = ExpensePatch(Some("Rent 16.06.2026"), None, None)
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <- algebra(capturing(captured, """{"error":0}""")).editExpense(ExpenseId(42), patch)
          json <- captured.get
        yield
          val expense = json.hcursor.downField("Expense")
          expense.get[String]("name") shouldBe Right("Rent 16.06.2026")
          expense.downField("attachment").succeeded shouldBe false
          expense.downField("comment").succeeded shouldBe false
      test.unsafeRunSync()
    }

    "omits every optional field when the patch is empty" in {
      val test =
        for
          captured <- Ref.of[IO, Json](Json.Null)
          _ <-
            algebra(capturing(captured, """{"error":0}""")).editExpense(ExpenseId(42), ExpensePatch(None, None, None))
          json <- captured.get
        yield
          val expense = json.hcursor.downField("Expense")
          expense.get[Long]("id") shouldBe Right(42)
          expense.downField("name").succeeded shouldBe false
          expense.downField("comment").succeeded shouldBe false
          expense.downField("attachment").succeeded shouldBe false
      test.unsafeRunSync()
    }
  }

  "listExpenses" - {
    "decodes expenses from a single page, taking the date from Superfaktura's datetime field" in {
      val body =
        """{"pageCount":1,"items":[{"Expense":{"id":7,"name":"SHELL","amount":73.71,"currency":"EUR","created":"2026-06-16 00:00:00"}}]}"""
      algebra(respond(body))
        .listExpenses(window)
        .map(_ shouldBe List(Expense(
          ExpenseId(7),
          "SHELL",
          Money(BigDecimal("73.71"), "EUR"),
          LocalDate.of(2026, 6, 16),
          None
        )))
        .unsafeRunSync()
    }

    "follows pagination across pages" in {
      val app = HttpApp[IO]: request =>
        val body =
          if request.uri.path.renderString.contains("page:2") then
            """{"pageCount":2,"items":[{"Expense":{"id":2,"name":"B","amount":2.00,"currency":"EUR","created":"2026-06-17"}}]}"""
          else
            """{"pageCount":2,"items":[{"Expense":{"id":1,"name":"A","amount":1.00,"currency":"EUR","created":"2026-06-16"}}]}"""
        Response[IO](Status.Ok).withEntity(parse(body).toOption.get).pure[IO]
      algebra(clientOf(app))
        .listExpenses(window)
        .map(_.map(_.id) shouldBe List(ExpenseId(1), ExpenseId(2)))
        .unsafeRunSync()
    }

    "fails with CliError.Decode on a malformed item" in {
      val body =
        """{"pageCount":1,"items":[{"Expense":{"id":7,"name":"X","amount":1.00,"currency":"EUR","created":"2026-13-40"}}]}"""
      algebra(respond(body))
        .listExpenses(window)
        .attempt
        .map {
          case Left(_: CliError.Decode) => succeed
          case other => fail(s"expected CliError.Decode, got: $other")
        }
        .unsafeRunSync()
    }
  }
end SuperfakturaClientTest
