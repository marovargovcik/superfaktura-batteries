package superfaktura.cli

import pureconfig.ConfigReader

case class Secret(value: String):
  override def toString: String = "<redacted>"

object Secret:
  given ConfigReader[Secret] = ConfigReader[String].map(Secret(_))
