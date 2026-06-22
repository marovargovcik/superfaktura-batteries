package superfaktura.rule

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

enum RuleMatch:
  case ExactName(name: String)
  case PartialName(fragment: String)
  case ExactRecipient(iban: String)

object RuleMatch:
  private given Configuration = Configuration.default.withDiscriminator("type")
  given Codec[RuleMatch] = ConfiguredCodec.derived[RuleMatch]
