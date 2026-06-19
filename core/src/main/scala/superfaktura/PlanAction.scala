package superfaktura

enum PlanAction:
  case CreateExpense(ref: ExternalRef, expense: CandidateExpense, attach: Option[ReceiptRef])
  case AttachToExisting(expenseId: ExpenseId, attachment: ReceiptRef)
  case SkipDuplicate(ref: ExternalRef, reason: String, matched: ExpenseId)
  case NeedsResolution(ref: ExternalRef, candidates: List[ExpenseId], reason: String)
