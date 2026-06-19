package superfaktura

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

enum PlanAction:
  case CreateExpense(ref: ExternalRef, expense: CandidateExpense, attach: Option[ReceiptRef])
  case AttachToExisting(expenseId: ExpenseId, attachment: ReceiptRef)
  case SkipDuplicate(ref: ExternalRef, reason: String, matched: ExpenseId)
  case NeedsResolution(ref: ExternalRef, candidates: List[ExpenseId], reason: String)
  case FlagReceipt(receipt: ReceiptRef, reason: String)

object PlanAction:
  private given Configuration = Configuration.default.withDiscriminator("type")
  given Codec[PlanAction] = ConfiguredCodec.derived[PlanAction]
