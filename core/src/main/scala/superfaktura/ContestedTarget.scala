package superfaktura

// Several receipts matching the same target in the amount + date window.
case class ContestedTarget(target: MatchTarget, receipts: List[Receipt])
