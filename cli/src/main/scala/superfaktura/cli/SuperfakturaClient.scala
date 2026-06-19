package superfaktura.cli

import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.{DecodeFailure, Header, Method, Request, Uri}
import org.typelevel.ci.*
import superfaktura.{
  CliError,
  DateWindow,
  Expense,
  ExpenseId,
  ExpensePatch,
  Money,
  NewExpense,
  ReceiptBytes,
  SuperfakturaAlgebra
}

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
          val parsed =
            for
              pageCount <- json.hcursor.get[Int]("pageCount").leftMap(_.getMessage)
              rawItems <- json.hcursor.get[List[Json]]("items").leftMap(_.getMessage)
              expenses <- rawItems.traverse(decodeExpense)
            yield (expenses, pageCount)
          parsed.leftMap[Throwable](CliError.Decode(_)).liftTo[F]

      def loop(page: Int, acc: List[Expense]): F[List[Expense]] =
        fetchPage(page).flatMap: (expenses, pageCount) =>
          val gathered = acc ++ expenses
          if page >= pageCount || expenses.isEmpty then gathered.pure[F] else loop(page + 1, gathered)

      loop(1, Nil)

    override def addExpense(request: NewExpense, attachment: Option[ReceiptBytes]): F[ExpenseId] =
      // Per the VAT decision the bank gross is recorded as `amount` with `vat = 0` (no net/VAT split);
      // every statement line is an already-paid `invoice`. The receipt rides in the same create call.
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
          "already_paid" := 1,
          "attachment" := encode(attachment)
        )
      )
      post("expenses/add", body).flatMap: json =>
        json.hcursor
          .downField("data")
          .downField("Expense")
          .get[Long]("id")
          .leftMap[Throwable](error => CliError.Decode(s"missing expense id: ${error.getMessage}"))
          .map(ExpenseId(_))
          .liftTo[F]

    override def editExpense(id: ExpenseId, patch: ExpensePatch): F[Unit] =
      val body = Json.obj("Expense" := Json.obj("id" := id.value, "attachment" := encode(patch.attachment)))
      post("expenses/edit", body).void

    private def encode(attachment: Option[ReceiptBytes]): Option[String] =
      attachment.map(bytes => Base64.getEncoder.encodeToString(bytes.value.toArray))

    private def get(path: String): F[Json] =
      resolve(path).flatMap(uri => runChecked(Request[F](Method.GET, uri).putHeaders(authHeader)))

    private def post(path: String, body: Json): F[Json] =
      resolve(path).flatMap(uri => runChecked(Request[F](Method.POST, uri).putHeaders(authHeader).withEntity(body)))

    private def runChecked(request: Request[F]): F[Json] =
      client.run(request).use: response =>
        response
          .as[Json]
          .adaptError { case failure: DecodeFailure => CliError.Decode(failure.message) }
          .flatMap: json =>
            val ok = response.status.isSuccess && json.hcursor.get[Int]("error").forall(_ == 0)
            if ok then json.pure[F]
            else Concurrent[F].raiseError(CliError.Api(response.status.code, errorMessage(json)))

    private def resolve(path: String): F[Uri] =
      Uri.fromString(s"${config.apiUrl.stripSuffix("/")}/$path") match
        case Right(uri) if uri.scheme.contains(Uri.Scheme.https) => uri.pure[F]
        case Right(_) =>
          Concurrent[F].raiseError(CliError.ConfigInvalid(s"apiUrl must use https: ${config.apiUrl}"))
        case Left(failure) =>
          Concurrent[F].raiseError(CliError.ConfigInvalid(s"invalid apiUrl '${config.apiUrl}': ${failure.message}"))

    private val authHeader: Header.Raw =
      val email = URLEncoder.encode(config.email, UTF_8)
      Header.Raw(
        ci"Authorization",
        s"SFAPI email=$email&apikey=${config.apiKey.value}&company_id=${config.companyId}&module=${config.module}"
      )
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
      comment <- expense.get[Option[String]]("comment").leftMap(_.getMessage)
    yield Expense(ExpenseId(id), name, Money(amount, currency), created, comment)

  private def errorMessage(json: Json): String =
    val field = json.hcursor.downField("error_message")
    field
      .as[String]
      .orElse(field.as[Map[String, List[String]]].map(_.values.flatten.mkString("; ")))
      .getOrElse("unknown error")
end SuperfakturaClient
