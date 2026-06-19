package superfaktura

enum CliError(message: String) extends Exception(message):
  case ConfigInvalid(detail: String) extends CliError(s"Invalid configuration: $detail")
  case FileAccess(detail: String) extends CliError(s"Cannot access file: $detail")
  case CsvInvalid(detail: String) extends CliError(s"Invalid bank statement CSV: $detail")
  case ImageInvalid(detail: String) extends CliError(s"Invalid image: $detail")
  case Api(status: Int, body: String) extends CliError(s"Superfaktura API error $status: $body")
  case Decode(detail: String) extends CliError(s"Unexpected Superfaktura response: $detail")
  case PlanInvalid(detail: String) extends CliError(s"Invalid plan: $detail")
