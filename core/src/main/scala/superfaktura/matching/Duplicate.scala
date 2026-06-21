package superfaktura.matching

import superfaktura.bank.CandidateExpense
import superfaktura.expense.ExpenseId

case class Duplicate(candidate: CandidateExpense, existingId: ExpenseId, reason: String)
