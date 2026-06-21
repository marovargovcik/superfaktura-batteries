package superfaktura

import superfaktura.bank.{BankStatementSourceAlgebra, CandidateExpense}
import superfaktura.expense.{Expense, SuperfakturaAlgebra}
import superfaktura.matching.{MatchTarget, MatchWindow, ReceiptMatcher}
import superfaktura.plan.{ExpensePlanner, Plan, PlanStore}
import superfaktura.receipt.{
  AttachmentFormat,
  OcrAlgebra,
  Receipt,
  ReceiptFile,
  ReceiptMarker,
  ReceiptRef,
  ReceiptSourceAlgebra
}

import cats.MonadThrow
import cats.syntax.all.*

import java.nio.file.Path

object PlanProgram:

  def run[F[_]: MonadThrow](csv: Path, receipts: Option[Path])(using
      bank: BankStatementSourceAlgebra[F],
      superfaktura: SuperfakturaAlgebra[F],
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F],
      store: PlanStore[F],
      reporter: ReporterAlgebra[F]
  ): F[Unit] =
    for
      transactions <- bank.read(csv)
      candidates = ExpensePlanner.toCandidates(transactions)
      scanned <- receipts.traverse(readReceipts).map(_.getOrElse((Nil, Nil)))
      (receiptPairs, unreadable) = scanned
      existing <- listExisting(candidates, receiptPairs.map { case (_, receipt) => receipt })
      plan = assemble(candidates, existing, receiptPairs, unreadable)
      _ <- store.save(plan)
      _ <- reporter.summary(plan)
    yield ()

  private def listExisting[F[_]: MonadThrow](candidates: List[CandidateExpense], receipts: List[Receipt])(using
      superfaktura: SuperfakturaAlgebra[F]
  ): F[List[Expense]] =
    if candidates.isEmpty && receipts.isEmpty then List.empty[Expense].pure[F]
    else superfaktura.listExpenses(ExpensePlanner.coverageWindow(candidates, receipts, MatchWindow.default))

  // A receipt pairs with whichever expense matches its amount + date — a new one the CSV will create, or one
  // already in Superfaktura — so a re-run dedupes the transactions and still attaches receipts to existing expenses.
  private def assemble(
      candidates: List[CandidateExpense],
      existing: List[Expense],
      receiptPairs: List[(ReceiptMarker, Receipt)],
      unreadable: List[ReceiptRef]
  ): Plan =
    val triage = ExpensePlanner.triage(candidates, existing)
    val targets = triage.toCreate.map(MatchTarget.Candidate(_)) ++ existing.map(MatchTarget.Existing(_))
    val (alreadyUploaded, fresh) = ExpensePlanner.partitionUploaded(receiptPairs, existing)
    val matched = ReceiptMatcher.matchReceipts(fresh, targets, MatchWindow.default)
    val base = ExpensePlanner.buildPlan(triage, matched, unreadable)
    Plan(base.items ++ alreadyUploaded)

  private def readReceipts[F[_]: MonadThrow](folder: Path)(using
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F]
  ): F[(List[(ReceiptMarker, Receipt)], List[ReceiptRef])] =
    receiptSource.list(folder).flatMap(_.traverse(readOne)).map: results =>
      (results.collect { case Right(pair) => pair }, results.collect { case Left(ref) => ref })

  private def readOne[F[_]: MonadThrow](file: ReceiptFile)(using
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F]
  ): F[Either[ReceiptRef, (ReceiptMarker, Receipt)]] =
    AttachmentFormat.of(file.ref).flatMap(_.ocrMedia) match
      case None => (Left(file.ref): Either[ReceiptRef, (ReceiptMarker, Receipt)]).pure[F]
      case Some(media) =>
        for
          bytes <- receiptSource.load(file.ref)
          result <- ocr.read(bytes, media)
        yield (result.amount, result.date) match
          case (Some(amount), Some(date)) =>
            Right((ExpensePlanner.receiptMarker(bytes), Receipt(file.ref, amount, date)))
          case _ => Left(file.ref)
end PlanProgram
