package superfaktura

case class MatchResult(
    paired: List[Pairing],
    ambiguous: List[AmbiguousReceipt],
    unmatchedReceipts: List[Receipt],
    unmatchedCandidates: List[CandidateExpense]
)
