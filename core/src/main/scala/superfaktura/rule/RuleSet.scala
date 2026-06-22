package superfaktura.rule

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class RuleSet(rules: List[Rule])

object RuleSet:
  val empty: RuleSet = RuleSet(Nil)
  given Codec[RuleSet] = deriveCodec
