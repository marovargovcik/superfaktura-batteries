package superfaktura.cli

import pureconfig.ConfigReader

case class AppConfig(superfaktura: SuperfakturaConfig) derives ConfigReader
