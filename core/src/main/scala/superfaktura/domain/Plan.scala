package superfaktura.domain

enum PlanItemStatus:
  case Pending, Applied, Skipped, Failed

enum MatchStrategy:
  case AmountAndDate, VisionOcr

enum PlanItem:
  case CreateExpense(ref: ExternalRef, expense: CandidateExpense, attach: Option[ReceiptRef], status: PlanItemStatus)
  case AttachToExisting(expenseId: ExpenseId, attachment: ReceiptRef, status: PlanItemStatus)
  case Skipped(ref: ExternalRef, reason: String, matched: ExpenseId)
  case NeedsResolution(ref: ExternalRef, candidates: List[ExpenseId], reason: String)

case class Plan(items: List[PlanItem])
