package superfaktura

import cats.MonadThrow
import cats.syntax.all.*

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
        for
          prepared <- attach.traverse(prepare)
          _ <- superfaktura.addExpense(ExpensePlanner.newExpense(ref, candidate), prepared.flatten)
        yield item.copy(status = PlanItemStatus.Applied)
      case PlanItem(PlanAction.AttachToExisting(expenseId, receipt), PlanItemStatus.Pending) =>
        prepare(receipt).flatMap:
          case Some(bytes) =>
            superfaktura.editExpense(expenseId, ExpensePatch(Some(bytes))).as(item.copy(status =
              PlanItemStatus.Applied
            ))
          case None => item.copy(status = PlanItemStatus.Failed).pure[F]
      case other => other.pure[F]

  // Loads the receipt and fits it under the attachment cap; None means it could not be made to fit.
  private def prepare[F[_]: MonadThrow](receipt: ReceiptRef)(using
      receiptSource: ReceiptSourceAlgebra[F],
      imagePrep: ImagePrepAlgebra[F]
  ): F[Option[ReceiptBytes]] =
    formatOf(receipt) match
      case None => Option.empty[ReceiptBytes].pure[F]
      case Some(format) =>
        for
          bytes <- receiptSource.load(receipt)
          prepared <- imagePrep.fit(bytes, format)
        yield prepared match
          case PreparedAttachment.Fitted(fitted) => Some(fitted)
          case PreparedAttachment.TooLarge(_) => None

  private def formatOf(receipt: ReceiptRef): Option[AttachmentFormat] =
    receipt.path.split("\\.").lastOption.flatMap(AttachmentFormat.fromExtension)
end ApplyProgram
