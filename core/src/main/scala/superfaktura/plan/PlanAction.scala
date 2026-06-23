package superfaktura.plan

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import superfaktura.bank.{CandidateExpense, ExternalRef}
import superfaktura.expense.ExpenseId
import superfaktura.receipt.ReceiptRef

enum PlanAction:
  case CreateExpense(ref: ExternalRef, expense: CandidateExpense, attach: Option[ReceiptRef])
  case AttachToExisting(expenseId: ExpenseId, attachment: ReceiptRef, comment: Option[String])
  case SkipDuplicate(ref: ExternalRef, reason: String, matched: ExpenseId)
  case RenameExpense(expenseId: ExpenseId, name: String)
  case FlagReceipt(receipt: ReceiptRef, reason: String)
  case ReceiptAlreadyUploaded(receipt: ReceiptRef, expense: ExpenseId)

object PlanAction:
  private given Configuration = Configuration.default.withDiscriminator("type")
  given Codec[PlanAction] = ConfiguredCodec.derived[PlanAction]
