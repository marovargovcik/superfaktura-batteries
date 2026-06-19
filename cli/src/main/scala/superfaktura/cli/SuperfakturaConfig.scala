package superfaktura.cli

import pureconfig.ConfigReader

case class SuperfakturaConfig(
    apiUrl: String,
    email: String,
    apiKey: Secret,
    companyId: String,
    module: String
) derives ConfigReader
