package superfaktura.bank

import superfaktura.Money

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.LocalDate

case class CandidateExpense(externalRef: ExternalRef, name: String, amount: Money, occurredOn: LocalDate)

object CandidateExpense:
  given Codec[CandidateExpense] = deriveCodec
