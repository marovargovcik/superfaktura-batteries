package superfaktura.bank

import java.time.LocalDate

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import superfaktura.Money

case class CandidateExpense(externalRef: ExternalRef, name: String, amount: Money, occurredOn: LocalDate)

object CandidateExpense:
  given Codec[CandidateExpense] = deriveCodec
