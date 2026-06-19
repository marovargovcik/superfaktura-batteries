package superfaktura

enum CliError(message: String) extends Exception(message):
  case ConfigInvalid(detail: String) extends CliError(s"Invalid configuration: $detail")
  case CsvInvalid(detail: String) extends CliError(s"Invalid bank statement CSV: $detail")
  case Api(status: Int, body: String) extends CliError(s"Superfaktura API error $status: $body")
  case PlanInvalid(detail: String) extends CliError(s"Invalid plan: $detail")
