package superfaktura.cli

import pureconfig.ConfigReader

case class ClaudeConfig(apiUrl: String, apiKey: Secret, model: String, maxTokens: Int) derives ConfigReader
