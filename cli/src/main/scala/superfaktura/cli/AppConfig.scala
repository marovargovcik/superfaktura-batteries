package superfaktura.cli

import pureconfig.ConfigReader

case class Secret(value: String):
  override def toString: String = "<redacted>"

object Secret:
  given ConfigReader[Secret] = ConfigReader[String].map(Secret(_))

case class SuperfakturaConfig(
    apiUrl: String,
    email: String,
    apiKey: Secret,
    companyId: String,
    module: String
) derives ConfigReader

case class AppConfig(superfaktura: SuperfakturaConfig) derives ConfigReader
