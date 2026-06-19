package superfaktura.cli

import pureconfig.ConfigReader

case class SuperfakturaConfig(
    apiUrl: String,
    email: String,
    apiKey: String,
    companyId: String,
    module: String
) derives ConfigReader

case class AppConfig(superfaktura: SuperfakturaConfig) derives ConfigReader
