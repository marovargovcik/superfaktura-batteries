package superfaktura.domain

enum PlanItemStatus:
  case Pending, Applied, Skipped, Failed

enum PlanAction:
  case CreateExpense(ref: ExternalRef, expense: CandidateExpense, attach: Option[ReceiptRef])
  case AttachToExisting(expenseId: ExpenseId, attachment: ReceiptRef)
  case SkipDuplicate(ref: ExternalRef, reason: String, matched: ExpenseId)
  case NeedsResolution(ref: ExternalRef, candidates: List[ExpenseId], reason: String)

case class PlanItem(action: PlanAction, status: PlanItemStatus)

case class Plan(items: List[PlanItem])
