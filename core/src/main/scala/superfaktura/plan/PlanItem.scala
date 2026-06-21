package superfaktura.plan

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class PlanItem(action: PlanAction, status: PlanItemStatus)

object PlanItem:
  given Codec[PlanItem] = deriveCodec
