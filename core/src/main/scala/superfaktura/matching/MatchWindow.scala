package superfaktura.matching

case class MatchWindow(daysBefore: Long, daysAfter: Long)

object MatchWindow:
  // Bank processing lags the purchase by 1–3 days (the transaction posts on or after the receipt
  // date); the 1 day before absorbs a little slack the other way.
  val default: MatchWindow = MatchWindow(daysBefore = 1, daysAfter = 3)
