package superfaktura

case class Duplicate(candidate: CandidateExpense, existingId: ExpenseId, reason: String)
