package superfaktura

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class Plan(items: List[PlanItem])

object Plan:
  given Codec[Plan] = deriveCodec
