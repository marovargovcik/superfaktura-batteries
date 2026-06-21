package superfaktura.matching

import superfaktura.receipt.Receipt

// Several receipts matching the same target in the amount + date window.
case class ContestedTarget(target: MatchTarget, receipts: List[Receipt])
