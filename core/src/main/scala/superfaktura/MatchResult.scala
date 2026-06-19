package superfaktura

case class MatchResult(
    paired: List[Pairing],
    ambiguousReceipts: List[AmbiguousReceipt],
    contestedTargets: List[ContestedTarget],
    unmatchedReceipts: List[Receipt],
    unmatchedTargets: List[MatchTarget]
)

object MatchResult:
  val empty: MatchResult = MatchResult(Nil, Nil, Nil, Nil, Nil)
