package superfaktura.receipt

import io.circe.{Codec, Decoder, Encoder}

case class ReceiptRef(path: String)

object ReceiptRef:
  given Codec[ReceiptRef] =
    Codec.from(Decoder.decodeString.map(ReceiptRef(_)), Encoder.encodeString.contramap(_.path))
