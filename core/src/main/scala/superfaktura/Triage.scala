package superfaktura

case class Duplicate(candidate: CandidateExpense, existing: ExpenseId, reason: String)

case class Triage(toCreate: List[CandidateExpense], duplicates: List[Duplicate])
