package superfaktura.plan

import io.circe.{Codec, Decoder, Encoder}

enum PlanItemStatus:
  case Pending, Applied, Skipped, Failed

object PlanItemStatus:
  given Codec[PlanItemStatus] =
    Codec.from(
      Decoder.decodeString.emap(name => values.find(_.toString == name).toRight(s"unknown status: $name")),
      Encoder.encodeString.contramap(_.toString)
    )
