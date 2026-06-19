package superfaktura.cli

import pureconfig.ConfigReader

case class AppConfig(superfaktura: SuperfakturaConfig, claude: ClaudeConfig) derives ConfigReader
