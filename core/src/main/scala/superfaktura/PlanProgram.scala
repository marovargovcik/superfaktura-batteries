package superfaktura

import java.nio.file.Path

import cats.MonadThrow
import cats.syntax.all.*
import superfaktura.bank.{BankStatementSourceAlgebra, CandidateExpense, ExternalRef}
import superfaktura.expense.{Expense, SuperfakturaAlgebra}
import superfaktura.matching.{MatchTarget, MatchWindow, ReceiptMatcher}
import superfaktura.plan.{Duplicate, ExpensePlanner, Plan, PlanAction, PlanItem, PlanItemStatus, PlanStore, Triage}
import superfaktura.receipt.{
  AttachmentFormat,
  OcrAlgebra,
  Receipt,
  ReceiptFile,
  ReceiptMarker,
  ReceiptRef,
  ReceiptSourceAlgebra
}
import superfaktura.rule.RuleStore

object PlanProgram:

  def run[F[_]: MonadThrow](csv: Path, receipts: Option[Path])(using
      bank: BankStatementSourceAlgebra[F],
      superfaktura: SuperfakturaAlgebra[F],
      receiptSource: ReceiptSourceAlgebra[F],
      ocr: OcrAlgebra[F],
      store: PlanStore[F],
      reporter: ReporterAlgebra[F],
      ruleStore: RuleStore[F]
  ): F[Unit] =
    for
      rules <- ruleStore.load
      transactions <- bank.read(csv)
      candidates = ExpensePlanner.toCandidates(transactions, rules)
      ruleRenames = ExpensePlanner.ruleRenames(transactions, rules)
      ruleAttachments = ExpensePlanner.ruleAttachments(transactions, rules)
      scanned <- receipts.traverse(readReceipts).map(_.getOrElse((Nil, Nil)))
      (receiptPairs, unreadable) = scanned
      existing <- listExisting(candidates, receiptPairs.map { case (_, receipt) => receipt })
      triage = ExpensePlanner.triage(candidates, existing)
      plan <- assemble(triage, existing, receiptPairs, unreadable, ruleAttachments, ruleRenames)
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
  private def assemble[F[_]: MonadThrow](
      triage: Triage,
      existing: List[Expense],
      receiptPairs: List[(ReceiptMarker, Receipt)],
      unreadable: List[ReceiptRef],
      ruleAttachments: Map[ExternalRef, ReceiptRef],
      ruleRenames: Map[ExternalRef, String]
  )(using receiptSource: ReceiptSourceAlgebra[F]): F[Plan] =
    val createRefs = triage.toCreate.map(_.externalRef).toSet
    val (createAttachments, existingAttachments) =
      ruleAttachments.partition { case (ref, _) => createRefs.contains(ref) }
    for
      (presentCreate, missingCreate) <- resolveRuleAttachments(createAttachments)
      (existingAttachItems, missingExisting) <- resolveExistingAttachments(triage.duplicates, existingAttachments)
    yield
      val targets = triage.toCreate.map(MatchTarget.Candidate(_)) ++ existing.map(MatchTarget.Existing(_))
      val (alreadyUploaded, fresh) = ExpensePlanner.partitionUploaded(receiptPairs, existing)
      val matched = ReceiptMatcher.matchReceipts(fresh, targets, MatchWindow.default)
      val base = ExpensePlanner.buildPlan(triage, matched, unreadable, presentCreate, ruleRenames)
      val missing = ExpensePlanner.flagMissingAttachments(missingCreate ++ missingExisting)
      Plan(base.items ++ alreadyUploaded ++ existingAttachItems ++ missing)

  // A rule names an explicit attachment path for a new expense; one that no longer exists is flagged here rather
  // than left to fail the whole `apply` run when its bytes can't be read.
  private def resolveRuleAttachments[F[_]: MonadThrow](attachments: Map[ExternalRef, ReceiptRef])(using
      receiptSource: ReceiptSourceAlgebra[F]
  ): F[(Map[ExternalRef, ReceiptRef], List[ReceiptRef])] =
    attachments.toList
      .traverse((ref, receipt) => receiptSource.exists(receipt).map(exists => (ref, receipt, exists)))
      .map: resolved =>
        val present = resolved.collect { case (ref, receipt, true) => ref -> receipt }.toMap
        val missing = resolved.collect { case (_, receipt, false) => receipt }
        (present, missing)

  // A rule may also name a fixed attachment for an already-booked expense. Attach it unless that file's content
  // hash is already recorded in the expense's comment (so re-runs don't re-upload); flag a path whose file is gone.
  private def resolveExistingAttachments[F[_]: MonadThrow](
      duplicates: List[Duplicate],
      attachments: Map[ExternalRef, ReceiptRef]
  )(using receiptSource: ReceiptSourceAlgebra[F]): F[(List[PlanItem], List[ReceiptRef])] =
    val targeted =
      duplicates.flatMap(duplicate => attachments.get(duplicate.candidate.externalRef).map(duplicate.existing -> _))
    targeted.traverse((existing, ref) => attachToExisting(existing, ref)).map: resolved =>
      (resolved.collect { case Right(Some(item)) => item }, resolved.collect { case Left(ref) => ref })

  private def attachToExisting[F[_]: MonadThrow](existing: Expense, ref: ReceiptRef)(using
      receiptSource: ReceiptSourceAlgebra[F]
  ): F[Either[ReceiptRef, Option[PlanItem]]] =
    receiptSource.exists(ref).flatMap:
      case false => (Left(ref): Either[ReceiptRef, Option[PlanItem]]).pure[F]
      case true =>
        receiptSource.load(ref).map: bytes =>
          if ExpensePlanner.receiptMarkers(existing.comment).contains(ExpensePlanner.receiptMarker(bytes)) then
            Right(None)
          else
            Right(Some(PlanItem(
              PlanAction.AttachToExisting(existing.id, ref, existing.comment),
              PlanItemStatus.Pending
            )))

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
