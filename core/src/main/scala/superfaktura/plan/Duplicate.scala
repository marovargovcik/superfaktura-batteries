package superfaktura.plan

import superfaktura.bank.CandidateExpense
import superfaktura.expense.Expense

case class Duplicate(candidate: CandidateExpense, existing: Expense, reason: String)
