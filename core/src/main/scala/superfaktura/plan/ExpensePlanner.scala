package superfaktura.plan

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import superfaktura.DateWindow
import superfaktura.bank.{CandidateExpense, ExternalRef, Transaction, TransactionType}
import superfaktura.expense.{Expense, NewExpense}
import superfaktura.matching.{MatchResult, MatchTarget, MatchWindow, Pairing}
import superfaktura.receipt.{Receipt, ReceiptBytes, ReceiptMarker, ReceiptRef}
import superfaktura.rule.{Rule, Rules, RuleSet}

object ExpensePlanner:

  def toCandidates(transactions: List[Transaction], rules: RuleSet = RuleSet.empty): List[CandidateExpense] =
    transactions.collect:
      case transaction if transaction.direction == TransactionType.Debit =>
        CandidateExpense(
          externalRef = externalRef(transaction),
          name = candidateName(transaction, rules),
          amount = transaction.amount,
          occurredOn = transaction.date
        )

  private def matchedRule(transaction: Transaction, rules: RuleSet): Option[Rule] =
    Rules.firstMatch(rules, expenseName(transaction), transaction.counterpartyIban)

  // A rename rule replaces only the human-visible name; the external ref still hashes the raw CSV
  // fields, so renaming never affects de-duplication on re-runs.
  private def candidateName(transaction: Transaction, rules: RuleSet): String =
    matchedRule(transaction, rules).flatMap(_.rename) match
      case Some(template) => Rules.renderName(template, transaction.date)
      case None => expenseName(transaction)

  def ruleAttachments(transactions: List[Transaction], rules: RuleSet): Map[ExternalRef, ReceiptRef] =
    transactions
      .filter(_.direction == TransactionType.Debit)
      .flatMap: transaction =>
        matchedRule(transaction, rules).flatMap(_.attach).map(path => externalRef(transaction) -> ReceiptRef(path))
      .toMap

  def flagMissingAttachments(missing: List[ReceiptRef]): List[PlanItem] =
    missing.map(ref => PlanItem(PlanAction.FlagReceipt(ref, "rule attachment file not found"), PlanItemStatus.Skipped))

  def triage(candidates: List[CandidateExpense], existing: List[Expense]): Triage =
    val (duplicates, toCreate) = candidates.partitionMap: candidate =>
      existing.find(matchesRef(candidate, _)) match
        case Some(expense) => Left(Duplicate(candidate, expense, "matching external ref already in Superfaktura"))
        case None => Right(candidate)
    Triage(toCreate, duplicates)

  def buildPlan(triage: Triage): Plan = buildPlan(triage, MatchResult.empty, Nil)

  def buildPlan(
      triage: Triage,
      matched: MatchResult,
      unreadableReceipts: List[ReceiptRef],
      ruleAttachments: Map[ExternalRef, ReceiptRef] = Map.empty
  ): Plan =
    // A rule's fixed attachment wins over an OCR-paired receipt for the same candidate.
    val attachByCandidate = matched.paired.collect {
      case Pairing(receipt, MatchTarget.Candidate(candidate)) => candidate.externalRef -> receipt.ref
    }.toMap ++ ruleAttachments
    val creates = triage.toCreate.map: candidate =>
      PlanItem(
        PlanAction.CreateExpense(candidate.externalRef, candidate, attachByCandidate.get(candidate.externalRef)),
        PlanItemStatus.Pending
      )
    // `comment` snapshots the expense's note at plan time so apply appends the marker rather than clobbering it;
    // a concurrent edit between plan and apply is overwritten — accepted for this single-user CLI.
    val attaches = matched.paired.collect:
      case Pairing(receipt, MatchTarget.Existing(expense)) =>
        PlanItem(PlanAction.AttachToExisting(expense.id, receipt.ref, expense.comment), PlanItemStatus.Pending)
    val skips = triage.duplicates.map: duplicate =>
      PlanItem(
        PlanAction.SkipDuplicate(duplicate.candidate.externalRef, duplicate.reason, duplicate.existing.id),
        PlanItemStatus.Skipped
      )
    Plan(creates ++ attaches ++ skips ++ receiptFlags(matched, unreadableReceipts))

  private def receiptFlags(matched: MatchResult, unreadableReceipts: List[ReceiptRef]): List[PlanItem] =
    val unmatched =
      matched.unmatchedReceipts.map(receipt => receipt.ref -> "no transaction matches its amount and date")
    val ambiguous = matched.ambiguousReceipts.map: entry =>
      entry.receipt.ref -> s"matches ${entry.targets.size} transactions; resolve manually"
    val contested = matched.contestedTargets
      .flatMap(_.receipts)
      .map(receipt => receipt.ref -> "several receipts match the same transaction")
    val unreadable = unreadableReceipts.map(ref => ref -> "could not read the amount and date")
    (unmatched ++ ambiguous ++ contested ++ unreadable).distinctBy { case (ref, _) => ref }.map { case (ref, reason) =>
      PlanItem(PlanAction.FlagReceipt(ref, reason), PlanItemStatus.Skipped)
    }

  // The window of existing expenses to fetch: it must cover both the CSV dates (for dedup) and every
  // date a receipt could attach to (for pairing against existing expenses).
  def coverageWindow(candidates: List[CandidateExpense], receipts: List[Receipt], window: MatchWindow): DateWindow =
    val lows = candidates.map(_.occurredOn) ++ receipts.map(_.date.minusDays(window.daysBefore))
    val highs = candidates.map(_.occurredOn) ++ receipts.map(_.date.plusDays(window.daysAfter))
    DateWindow(lows.minBy(_.toEpochDay), highs.maxBy(_.toEpochDay))

  // Superfaktura has no custom-metadata field, so the human-visible comment is the only place
  // to persist a machine-readable ref for de-duplicating on re-runs.
  def refMarker(ref: ExternalRef): String = s"sfref:${ref.value}"

  def receiptMarker(receipt: ReceiptBytes): ReceiptMarker =
    ReceiptMarker.of(hex(MessageDigest.getInstance("SHA-256").digest(receipt.value.toArray)))

  def appendMarker(comment: Option[String], marker: ReceiptMarker): Option[String] =
    val tokens = comment.toList.flatMap(_.split("\\s+"))
    if tokens.contains(marker.value) then comment
    else Some((comment.toList :+ marker.value).mkString(" "))

  def receiptMarkers(comment: Option[String]): Set[ReceiptMarker] =
    comment.toList.flatMap(_.split("\\s+")).flatMap(ReceiptMarker.parse).toSet

  // Split scanned receipts into those already attached to an existing expense (matched by their content-hash
  // marker recorded in its comment) and the rest that still need pairing.
  def partitionUploaded(
      receiptPairs: List[(ReceiptMarker, Receipt)],
      existing: List[Expense]
  ): (List[PlanItem], List[Receipt]) =
    val markerToExpense = existing.flatMap(e => receiptMarkers(e.comment).map(_ -> e.id)).toMap
    receiptPairs.partitionMap: (marker, receipt) =>
      markerToExpense.get(marker) match
        case Some(expenseId) =>
          Left(PlanItem(PlanAction.ReceiptAlreadyUploaded(receipt.ref, expenseId), PlanItemStatus.Skipped))
        case None => Right(receipt)

  def newExpense(ref: ExternalRef, candidate: CandidateExpense): NewExpense =
    NewExpense(
      name = candidate.name,
      amount = candidate.amount,
      created = candidate.occurredOn,
      variableSymbol = None,
      comment = Some(refMarker(ref))
    )

  private def matchesRef(candidate: CandidateExpense, expense: Expense): Boolean =
    expense.comment.exists(_.contains(refMarker(candidate.externalRef)))

  def render(plan: Plan): String =
    val header = s"Plan: ${plan.items.size} item(s)"
    (header :: plan.items.map(renderItem)).mkString("\n")

  private def renderItem(item: PlanItem): String =
    val status = item.status.toString
    item.action match
      case PlanAction.CreateExpense(_, expense, attach) =>
        val attachment = attach.fold("")(receipt => s" + ${receipt.path}")
        s"[$status] create '${expense.name}' ${expense.amount.amount} ${expense.amount.currency}$attachment"
      case PlanAction.AttachToExisting(expenseId, attachment, _) =>
        s"[$status] attach ${attachment.path} to expense ${expenseId.value}"
      case PlanAction.SkipDuplicate(_, reason, matched) =>
        s"[$status] skip duplicate of expense ${matched.value}: $reason"
      case PlanAction.NeedsResolution(_, candidates, reason) =>
        s"[$status] needs resolution ($reason); candidates: ${candidates.map(_.value).mkString(", ")}"
      case PlanAction.FlagReceipt(receipt, reason) =>
        s"[$status] flag receipt ${receipt.path}: $reason"
      case PlanAction.ReceiptAlreadyUploaded(receipt, expense) =>
        s"[$status] skip ${receipt.path}: already uploaded to expense ${expense.value}"

  private def expenseName(transaction: Transaction): String =
    cardMerchant(transaction)
      .orElse(transaction.recipientInfo)
      .getOrElse(transaction.description)

  // Tatra banka packs card-payment recipient info as
  // "<masked-PAN> <city> <cardholder> <yyyymmdd> <hh:mm:ss> <amount><CCY> <merchant>".
  private val merchantAfterAmount = """\d+\.\d{2}[A-Z]{3}\s+(.+)$""".r

  private def cardMerchant(transaction: Transaction): Option[String] =
    Option
      .when(isCardPayment(transaction))(transaction.recipientInfo)
      .flatten
      .flatMap(info => merchantAfterAmount.findFirstMatchIn(info).map(_.group(1).trim))

  private def isCardPayment(transaction: Transaction): Boolean =
    transaction.description.startsWith("GP NÁKUP POS") || transaction.description.startsWith("INT NÁKUP POS")

  private def externalRef(transaction: Transaction): ExternalRef =
    val fields = List(
      transaction.date.toString,
      transaction.amount.amount.toString,
      transaction.amount.currency,
      transaction.variableSymbol.getOrElse(""),
      transaction.specificSymbol.getOrElse(""),
      transaction.counterpartyIban.getOrElse(""),
      transaction.description
    )
    val canonical = fields.map(field => s"${field.length}:$field").mkString
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
    ExternalRef(hex(digest))

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString
end ExpensePlanner
