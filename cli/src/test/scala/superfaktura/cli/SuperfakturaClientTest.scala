package superfaktura.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.{HttpApp, Response, Status}
import org.typelevel.ci.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import superfaktura.*

import java.time.LocalDate

class SuperfakturaClientTest extends AnyFreeSpec with Matchers:

  private given SuperfakturaConfig =
    SuperfakturaConfig("https://sandbox.superfaktura.sk", "a+b@example.com", Secret("key"), "119297", "test 1.0")

  private val window = DateWindow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

  private def newExpense = NewExpense("SHELL", Money(BigDecimal("73.71"), "EUR"), LocalDate.of(2026, 6, 16), None, None)

  private def algebra(app: HttpApp[IO]): SuperfakturaAlgebra[IO] =
    given Client[IO] = Client.fromHttpApp(app)
    SuperfakturaClient.live[IO]

  private def respond(body: String, status: Status = Status.Ok): HttpApp[IO] =
    HttpApp[IO](_ => Response[IO](status).withEntity(parse(body).toOption.get).pure[IO])

  "addExpense" - {
    "posts the expense and returns the new id" in {
      val client = respond("""{"data":{"Expense":{"id":123}},"error":0}""")
      algebra(client).addExpense(newExpense).unsafeRunSync() shouldBe ExpenseId(123)
    }

    "raises CliError.Api when the body reports an error" in {
      val client = respond("""{"error":1,"error_message":"Chýbajúce údaje"}""")
      algebra(client).addExpense(newExpense).attempt.unsafeRunSync() match
        case Left(_: CliError.Api) => succeed
        case other => fail(s"expected CliError.Api, got: $other")
    }

    "sends a URL-encoded SFAPI Authorization header" in {
      val app = HttpApp[IO]: request =>
        val auth = request.headers.get(ci"Authorization").map(_.head.value).getOrElse("")
        if auth.startsWith("SFAPI email=a%2Bb%40example.com&apikey=key") then
          Response[IO](Status.Ok).withEntity(parse("""{"data":{"Expense":{"id":1}},"error":0}""").toOption.get).pure[IO]
        else
          Response[IO](Status.Forbidden).withEntity(parse("""{"error":1,"error_message":"no auth"}""").toOption.get)
            .pure[IO]
      algebra(app).addExpense(newExpense).unsafeRunSync() shouldBe ExpenseId(1)
    }
  }

  "listExpenses" - {
    "decodes expenses from a single page" in {
      val body =
        """{"pageCount":1,"items":[{"Expense":{"id":7,"name":"SHELL","amount":73.71,"currency":"EUR","created":"2026-06-16"}}]}"""
      algebra(respond(body)).listExpenses(window).unsafeRunSync() shouldBe
        List(Expense(ExpenseId(7), "SHELL", Money(BigDecimal("73.71"), "EUR"), LocalDate.of(2026, 6, 16)))
    }

    "follows pagination across pages" in {
      val app = HttpApp[IO]: request =>
        val body =
          if request.uri.path.renderString.contains("page:2") then
            """{"pageCount":2,"items":[{"Expense":{"id":2,"name":"B","amount":2.00,"currency":"EUR","created":"2026-06-17"}}]}"""
          else
            """{"pageCount":2,"items":[{"Expense":{"id":1,"name":"A","amount":1.00,"currency":"EUR","created":"2026-06-16"}}]}"""
        Response[IO](Status.Ok).withEntity(parse(body).toOption.get).pure[IO]
      algebra(app).listExpenses(window).unsafeRunSync().map(_.id) shouldBe List(ExpenseId(1), ExpenseId(2))
    }
  }
end SuperfakturaClientTest
