package superfaktura

import java.time.LocalDate

// A receipt can be paired either to a new expense derived from the CSV (use cases A/C) or to an
// expense already in Superfaktura (use case D); both expose the amount and date the matcher needs.
enum MatchTarget:
  case Candidate(candidate: CandidateExpense)
  case Existing(expense: Expense)

  def amount: Money = this match
    case Candidate(candidate) => candidate.amount
    case Existing(expense) => expense.amount

  def date: LocalDate = this match
    case Candidate(candidate) => candidate.occurredOn
    case Existing(expense) => expense.created
