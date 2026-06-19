package superfaktura.cli

import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Uri}
import org.typelevel.ci.*
import superfaktura.{CliError, DateWindow, Expense, ExpenseId, ExpensePatch, Money, NewExpense, SuperfakturaAlgebra}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.Base64
import scala.util.Try

object SuperfakturaClient:

  given live[F[_]: Concurrent](using client: Client[F], config: SuperfakturaConfig): SuperfakturaAlgebra[F] with

    override def listExpenses(window: DateWindow): F[List[Expense]] =
      def fetchPage(page: Int): F[(List[Expense], Int)] =
        get(
          s"expenses/index.json/created:3/created_since:${window.from}/created_to:${window.to}/per_page:100/page:$page/listinfo:1"
        ).flatMap: json =>
          val pageCount = json.hcursor.get[Int]("pageCount").getOrElse(1)
          val items = json.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
          items.traverse(decodeExpense).leftMap[Throwable](CliError.Api(200, _)).liftTo[F].map(_ -> pageCount)

      def loop(page: Int, acc: List[Expense]): F[List[Expense]] =
        fetchPage(page).flatMap: (expenses, pageCount) =>
          if page >= pageCount then (acc ++ expenses).pure[F] else loop(page + 1, acc ++ expenses)

      loop(1, Nil)

    override def addExpense(request: NewExpense): F[ExpenseId] =
      val body = Json.obj(
        "Expense" := Json.obj(
          "name" := request.name,
          "amount" := request.amount.amount,
          "vat" := 0,
          "currency" := request.amount.currency,
          "created" := request.created.toString,
          "variable" := request.variableSymbol,
          "comment" := request.comment,
          "type" := "invoice",
          "already_paid" := 1
        )
      )
      post("expenses/add", body).flatMap: json =>
        json.hcursor
          .downField("data")
          .downField("Expense")
          .get[Long]("id")
          .leftMap[Throwable](error => CliError.Api(200, s"missing expense id: ${error.getMessage}"))
          .map(ExpenseId(_))
          .liftTo[F]

    override def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit] =
      val attachment = patch.attachment.map(bytes => Base64.getEncoder.encodeToString(bytes.value.toArray))
      val body = Json.obj("Expense" := Json.obj("id" := id.value, "attachment" := attachment))
      post("expenses/edit", body).void

    private def get(path: String): F[Json] =
      runChecked(Request[F](Method.GET, uri(path)).putHeaders(authHeader))

    private def post(path: String, body: Json): F[Json] =
      runChecked(Request[F](Method.POST, uri(path)).putHeaders(authHeader).withEntity(body))

    private def runChecked(request: Request[F]): F[Json] =
      client.run(request).use: response =>
        response.as[Json].flatMap: json =>
          val ok = response.status.isSuccess && json.hcursor.get[Int]("error").forall(_ == 0)
          if ok then json.pure[F]
          else Concurrent[F].raiseError(CliError.Api(response.status.code, errorMessage(json)))

    private val authHeader: Header.Raw =
      val email = URLEncoder.encode(config.email, UTF_8)
      Header.Raw(
        ci"Authorization",
        s"SFAPI email=$email&apikey=${config.apiKey.value}&company_id=${config.companyId}&module=${config.module}"
      )

    private def uri(path: String): Uri = Uri.unsafeFromString(s"${config.apiUrl}/$path")
  end live

  private def decodeExpense(item: Json): Either[String, Expense] =
    val expense = item.hcursor.downField("Expense")
    for
      id <- expense.get[Long]("id").leftMap(_.getMessage)
      name <- expense.get[String]("name").leftMap(_.getMessage)
      amount <- expense.get[BigDecimal]("amount").leftMap(_.getMessage)
      currency <- expense.get[String]("currency").leftMap(_.getMessage)
      rawDate <- expense.get[String]("created").leftMap(_.getMessage)
      created <- Try(LocalDate.parse(rawDate)).toEither.leftMap(_ => s"invalid created date: '$rawDate'")
    yield Expense(ExpenseId(id), name, Money(amount, currency), created)

  private def errorMessage(json: Json): String =
    val field = json.hcursor.downField("error_message")
    field
      .as[String]
      .orElse(field.as[Map[String, List[String]]].map(_.values.flatten.mkString("; ")))
      .getOrElse("unknown error")
end SuperfakturaClient
