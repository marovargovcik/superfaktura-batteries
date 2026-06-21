package superfaktura

import cats.MonadThrow
import cats.syntax.all.*
import superfaktura.expense.{ExpensePatch, SuperfakturaAlgebra}
import superfaktura.plan.{ExpensePlanner, Plan, PlanAction, PlanItem, PlanItemStatus, PlanStore}
import superfaktura.receipt.{
  AttachmentFormat,
  ImagePrepAlgebra,
  PreparedAttachment,
  ReceiptBytes,
  ReceiptMarker,
  ReceiptRef,
  ReceiptSourceAlgebra
}

object ApplyProgram:

  def run[F[_]: MonadThrow](using
      store: PlanStore[F],
      superfaktura: SuperfakturaAlgebra[F],
      receiptSource: ReceiptSourceAlgebra[F],
      imagePrep: ImagePrepAlgebra[F],
      reporter: ReporterAlgebra[F]
  ): F[Unit] =
    for
      plan <- store.load
      applied <- plan.items.traverse(applyItem)
      updated = Plan(applied)
      _ <- store.save(updated)
      _ <- reporter.summary(updated)
    yield ()

  private def applyItem[F[_]: MonadThrow](item: PlanItem)(using
      superfaktura: SuperfakturaAlgebra[F],
      receiptSource: ReceiptSourceAlgebra[F],
      imagePrep: ImagePrepAlgebra[F]
  ): F[PlanItem] =
    item match
      case PlanItem(PlanAction.CreateExpense(ref, candidate, attach), PlanItemStatus.Pending) =>
        val base = ExpensePlanner.newExpense(ref, candidate)
        attach.flatTraverse(prepare).flatMap:
          case Some((marker, bytes)) =>
            val request = base.copy(comment = ExpensePlanner.appendMarker(base.comment, marker))
            superfaktura.addExpense(request, Some(bytes)).as(item.copy(status = PlanItemStatus.Applied))
          case None =>
            superfaktura.addExpense(base, None).as(item.copy(status = PlanItemStatus.Applied))
      case PlanItem(PlanAction.AttachToExisting(expenseId, receipt, comment), PlanItemStatus.Pending) =>
        prepare(receipt).flatMap:
          case Some((marker, bytes)) =>
            val patch = ExpensePatch(Some(bytes), ExpensePlanner.appendMarker(comment, marker))
            superfaktura.editExpense(expenseId, patch).as(item.copy(status = PlanItemStatus.Applied))
          // Unlike a create, the receipt is the whole point here, so one that won't fit fails the item.
          case None => item.copy(status = PlanItemStatus.Failed).pure[F]
      case other => other.pure[F]

  // Loads the receipt and fits it under the attachment cap; None means it could not be made to fit.
  private def prepare[F[_]: MonadThrow](receipt: ReceiptRef)(using
      receiptSource: ReceiptSourceAlgebra[F],
      imagePrep: ImagePrepAlgebra[F]
  ): F[Option[(ReceiptMarker, ReceiptBytes)]] =
    AttachmentFormat.of(receipt) match
      case None => Option.empty[(ReceiptMarker, ReceiptBytes)].pure[F]
      case Some(format) =>
        for
          bytes <- receiptSource.load(receipt)
          prepared <- imagePrep.fit(bytes, format)
        yield prepared match
          case PreparedAttachment.Fitted(fitted) => Some((ExpensePlanner.receiptMarker(bytes), fitted))
          case PreparedAttachment.TooLarge(_) => None
end ApplyProgram
