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
      plan <- assemble(candidates, received, unreadable)
      _ <- store.save(plan)
      _ <- reporter.summary(plan)
    yield ()

  // With a CSV, receipts pair with the new expenses it produces (use cases A/C); with receipts only,
  // they pair with expenses already in Superfaktura, windowed by the receipt dates (use case D).
  private def assemble[F[_]: MonadThrow](
      candidates: List[CandidateExpense],
      received: List[Receipt],
      unreadable: List[ReceiptRef]
  )(using superfaktura: SuperfakturaAlgebra[F]): F[Plan] =
    if candidates.nonEmpty then
      for existing <- superfaktura.listExpenses(ExpensePlanner.windowOf(candidates))
      yield
        val triage = ExpensePlanner.triage(candidates, existing)
        val targets = triage.toCreate.map(MatchTarget.Candidate(_))
        ExpensePlanner.buildPlan(
          triage,
          ReceiptMatcher.matchReceipts(received, targets, MatchWindow.default),
          unreadable
        )
    else if received.nonEmpty then
      for existing <- superfaktura.listExpenses(ExpensePlanner.listingWindow(received, MatchWindow.default))
      yield
        val targets = existing.map(MatchTarget.Existing(_))
        ExpensePlanner.buildPlan(
          Triage(Nil, Nil),
          ReceiptMatcher.matchReceipts(received, targets, MatchWindow.default),
          unreadable
        )
    else ExpensePlanner.buildPlan(Triage(Nil, Nil), MatchResult.empty, unreadable).pure[F]

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
    ocrMedia(file.ref) match
      case None => (Left(file.ref): Either[ReceiptRef, Receipt]).pure[F]
      case Some(media) =>
        for
          bytes <- receiptSource.load(file.ref)
          result <- ocr.read(bytes, media)
        yield (result.amount, result.date) match
          case (Some(amount), Some(date)) => Right(Receipt(file.ref, amount, date))
          case _ => Left(file.ref)

  private def ocrMedia(ref: ReceiptRef): Option[ReceiptMedia] =
    ref.path.split("\\.").lastOption.flatMap(AttachmentFormat.fromExtension).flatMap(_.ocrMedia)
end PlanProgram
