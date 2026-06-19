package superfaktura

// Bank processing lags the purchase by 1–3 days, so the transaction posts on or after the receipt date.
case class MatchWindow(daysBefore: Long, daysAfter: Long)

object MatchWindow:
  val default: MatchWindow = MatchWindow(daysBefore = 1, daysAfter = 3)
