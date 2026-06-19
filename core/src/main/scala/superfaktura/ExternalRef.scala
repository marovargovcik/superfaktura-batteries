package superfaktura

import io.circe.{Codec, Decoder, Encoder}

case class ExternalRef(value: String)

object ExternalRef:
  given Codec[ExternalRef] =
    Codec.from(Decoder.decodeString.map(ExternalRef(_)), Encoder.encodeString.contramap(_.value))
