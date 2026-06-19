package superfaktura

case class AmbiguousReceipt(receipt: Receipt, candidates: List[CandidateExpense])
