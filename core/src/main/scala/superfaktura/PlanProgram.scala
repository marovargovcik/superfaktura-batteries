package superfaktura

import cats.MonadThrow
import cats.syntax.all.*

import java.nio.file.Path

object PlanProgram:

  def run[F[_]: MonadThrow](csv: Option[Path], receipts: Option[Path])(using
      bank: BankStatementSourceAlgebra[F],
      superfaktura: SuperfakturaAlgebra[F],
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F],
      store: PlanStore[F],
      reporter: ReporterAlgebra[F]
  ): F[Unit] =
    for
      transactions <- csv.traverse(bank.read).map(_.getOrElse(Nil))
      candidates = ExpensePlanner.toCandidates(transactions)
      scanned <- receipts.traverse(readReceipts).map(_.getOrElse((Nil, Nil)))
      (received, unreadable) = scanned
      plan <- assemble(csv.isDefined, candidates, received, unreadable)
      _ <- store.save(plan)
      _ <- reporter.summary(plan)
    yield ()

  // A CSV (even one with no debits) means receipts pair with the new expenses it produces (use cases A/C);
  // receipts alone pair with expenses already in Superfaktura, windowed by the receipt dates (use case D).
  private def assemble[F[_]: MonadThrow](
      csvProvided: Boolean,
      candidates: List[CandidateExpense],
      received: List[Receipt],
      unreadable: List[ReceiptRef]
  )(using SuperfakturaAlgebra[F]): F[Plan] =
    if csvProvided then planForCandidates(candidates, received, unreadable)
    else if received.nonEmpty then planForExisting(received, unreadable)
    else ExpensePlanner.buildPlan(Triage(Nil, Nil), MatchResult.empty, unreadable).pure[F]

  private def planForCandidates[F[_]: MonadThrow](
      candidates: List[CandidateExpense],
      received: List[Receipt],
      unreadable: List[ReceiptRef]
  )(using SuperfakturaAlgebra[F]): F[Plan] =
    existingForDedup(candidates).map: existing =>
      val triage = ExpensePlanner.triage(candidates, existing)
      val targets = triage.toCreate.map(MatchTarget.Candidate(_))
      ExpensePlanner.buildPlan(triage, ReceiptMatcher.matchReceipts(received, targets, MatchWindow.default), unreadable)

  private def existingForDedup[F[_]: MonadThrow](
      candidates: List[CandidateExpense]
  )(using superfaktura: SuperfakturaAlgebra[F]): F[List[Expense]] =
    if candidates.isEmpty then List.empty[Expense].pure[F]
    else superfaktura.listExpenses(ExpensePlanner.windowOf(candidates))

  private def planForExisting[F[_]: MonadThrow](
      received: List[Receipt],
      unreadable: List[ReceiptRef]
  )(using superfaktura: SuperfakturaAlgebra[F]): F[Plan] =
    superfaktura.listExpenses(ExpensePlanner.listingWindow(received, MatchWindow.default)).map: existing =>
      val targets = existing.map(MatchTarget.Existing(_))
      ExpensePlanner.buildPlan(
        Triage(Nil, Nil),
        ReceiptMatcher.matchReceipts(received, targets, MatchWindow.default),
        unreadable
      )

  private def readReceipts[F[_]: MonadThrow](folder: Path)(using
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F]
  ): F[(List[Receipt], List[ReceiptRef])] =
    receiptSource.list(folder).flatMap(_.traverse(readOne)).map: results =>
      (results.collect { case Right(receipt) => receipt }, results.collect { case Left(ref) => ref })

  private def readOne[F[_]: MonadThrow](file: ReceiptFile)(using
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F]
  ): F[Either[ReceiptRef, Receipt]] =
    AttachmentFormat.of(file.ref).flatMap(_.ocrMedia) match
      case None => (Left(file.ref): Either[ReceiptRef, Receipt]).pure[F]
      case Some(media) =>
        for
          bytes <- receiptSource.load(file.ref)
          result <- ocr.read(bytes, media)
        yield (result.amount, result.date) match
          case (Some(amount), Some(date)) => Right(Receipt(file.ref, amount, date))
          case _ => Left(file.ref)
end PlanProgram
