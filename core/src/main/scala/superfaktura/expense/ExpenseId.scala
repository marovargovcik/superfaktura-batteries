package superfaktura.expense

import io.circe.{Codec, Decoder, Encoder}

case class ExpenseId(value: Long)

object ExpenseId:
  given Codec[ExpenseId] =
    Codec.from(Decoder.decodeLong.map(ExpenseId(_)), Encoder.encodeLong.contramap(_.value))
