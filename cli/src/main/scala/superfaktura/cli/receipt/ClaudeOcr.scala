package superfaktura.cli.receipt

import java.time.LocalDate
import java.util.Base64

import scala.util.Try

import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.syntax.*
import org.http4s.{DecodeFailure, Header, Method, Request, Response, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.typelevel.ci.*
import superfaktura.{CliError, Money}
import superfaktura.cli.ClaudeConfig
import superfaktura.receipt.{OcrAlgebra, OcrResult, ReceiptBytes, ReceiptMedia}

object ClaudeOcr:

  given live[F[_]: Concurrent](using client: Client[F], config: ClaudeConfig): OcrAlgebra[F] with

    override def read(receipt: ReceiptBytes, media: ReceiptMedia): F[OcrResult] =
      if config.apiKey.value.isEmpty then
        Concurrent[F].raiseError(CliError.ConfigInvalid("ANTHROPIC_API_KEY is not set (needed to OCR receipts)"))
      else readChecked(receipt, media)

    private def readChecked(receipt: ReceiptBytes, media: ReceiptMedia): F[OcrResult] =
      messagesUri.flatMap: uri =>
        val request = Request[F](Method.POST, uri)
          .putHeaders(
            Header.Raw(ci"x-api-key", config.apiKey.value),
            Header.Raw(ci"anthropic-version", anthropicVersion)
          )
          .withEntity(body(receipt, media))
        client.run(request).use(parse)

    private def body(receipt: ReceiptBytes, media: ReceiptMedia): Json =
      val data = Base64.getEncoder.encodeToString(receipt.value.toArray)
      val sourceBlock = media match
        case ReceiptMedia.Pdf => "document"
        case _ => "image"
      Json.obj(
        "model" := config.model,
        "max_tokens" := config.maxTokens,
        "tools" := List(tool),
        "tool_choice" := Json.obj("type" := "tool", "name" := toolName),
        "messages" := List(
          Json.obj(
            "role" := "user",
            "content" := List(
              Json.obj(
                "type" := sourceBlock,
                "source" := Json.obj("type" := "base64", "media_type" := media.mimeType, "data" := data)
              ),
              Json.obj("type" := "text", "text" := prompt)
            )
          )
        )
      )

    private def parse(response: Response[F]): F[OcrResult] =
      response
        .as[Json]
        .adaptError { case failure: DecodeFailure => CliError.Decode(failure.message) }
        .flatMap: json =>
          if !response.status.isSuccess then
            Concurrent[F].raiseError(CliError.Api(response.status.code, errorMessage(json)))
          else toolUseInput(json).map(parseInput).liftTo[F]

    private def messagesUri: F[Uri] =
      Uri.fromString(s"${config.apiUrl.stripSuffix("/")}/v1/messages") match
        case Right(uri) if uri.scheme.contains(Uri.Scheme.https) => uri.pure[F]
        case Right(_) =>
          Concurrent[F].raiseError(CliError.ConfigInvalid(s"Claude apiUrl must use https: ${config.apiUrl}"))
        case Left(failure) =>
          Concurrent[F].raiseError(
            CliError.ConfigInvalid(s"invalid Claude apiUrl '${config.apiUrl}': ${failure.message}")
          )

  end live

  private val toolName = "record_receipt"
  private val anthropicVersion = "2023-06-01"

  private val prompt =
    "Extract the grand total, its ISO 4217 currency code, and the date from this receipt using the " +
      "record_receipt tool. Omit any field that is not clearly legible."

  private val tool: Json = Json.obj(
    "name" := toolName,
    "description" := "Record the total amount, currency, and date printed on a receipt or invoice.",
    "input_schema" := Json.obj(
      "type" := "object",
      "properties" := Json.obj(
        "amount" := Json.obj("type" := "number", "description" := "the grand total"),
        "currency" := Json.obj("type" := "string", "description" := "ISO 4217 code, e.g. EUR"),
        "date" := Json.obj("type" := "string", "description" := "ISO date, YYYY-MM-DD")
      )
    )
  )

  private def toolUseInput(json: Json): Either[CliError, ACursor] =
    json.hcursor.downField("content").as[List[Json]].leftMap(failure => CliError.Decode(failure.getMessage)).flatMap:
      blocks =>
        blocks
          .find(_.hcursor.get[String]("type").toOption.contains("tool_use"))
          .map(_.hcursor.downField("input"))
          .toRight(CliError.Decode("Claude response had no tool_use block"))

  private def parseInput(input: ACursor): OcrResult =
    // An amount is only usable paired with its currency, so a total we cannot denominate is dropped, not guessed.
    val amount = (input.get[BigDecimal]("amount").toOption, input.get[String]("currency").toOption).mapN(Money(_, _))
    val date = input.get[String]("date").toOption.flatMap(raw => Try(LocalDate.parse(raw)).toOption)
    OcrResult(amount, date)

  private def errorMessage(json: Json): String =
    json.hcursor.downField("error").get[String]("message").getOrElse("unknown error")
end ClaudeOcr
