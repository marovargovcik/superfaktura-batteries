package superfaktura.matching

import superfaktura.receipt.Receipt

// One receipt matching several targets in the amount + date window.
case class AmbiguousReceipt(receipt: Receipt, targets: List[MatchTarget])
