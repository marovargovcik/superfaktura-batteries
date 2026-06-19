package superfaktura

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class Money(amount: BigDecimal, currency: String)

object Money:
  given Codec[Money] = deriveCodec
