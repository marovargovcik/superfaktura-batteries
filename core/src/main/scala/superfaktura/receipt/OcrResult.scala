package superfaktura.receipt

import superfaktura.Money

import java.time.LocalDate

// What the vision model could read off a receipt. Either field may be absent when illegible; the
// pipeline only forms a Receipt (and so can pair it) when both are present.
case class OcrResult(amount: Option[Money], date: Option[LocalDate])
