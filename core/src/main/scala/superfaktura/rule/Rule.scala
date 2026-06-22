package superfaktura.rule

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class Rule(when: RuleMatch, rename: Option[String], attach: Option[String])

object Rule:
  given Codec[Rule] = deriveCodec
