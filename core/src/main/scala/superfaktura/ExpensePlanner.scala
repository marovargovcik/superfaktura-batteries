package superfaktura

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ExpensePlanner:

  def toCandidates(transactions: List[Transaction]): List[CandidateExpense] =
    transactions.collect:
      case transaction if transaction.direction == TransactionType.Debit =>
        CandidateExpense(
          externalRef = externalRef(transaction),
          name = expenseName(transaction),
          amount = transaction.amount,
          occurredOn = transaction.date
        )

  def triage(candidates: List[CandidateExpense], existing: List[Expense]): Triage =
    val (duplicates, toCreate) = candidates.partitionMap: candidate =>
      existing.find(matchesRef(candidate, _)) match
        case Some(expense) => Left(Duplicate(candidate, expense.id, "matching external ref already in Superfaktura"))
        case None => Right(candidate)
    Triage(toCreate, duplicates)

  def buildPlan(triage: Triage): Plan =
    val creates = triage.toCreate.map: candidate =>
      PlanItem(PlanAction.CreateExpense(candidate.externalRef, candidate, None), PlanItemStatus.Pending)
    val skips = triage.duplicates.map: duplicate =>
      PlanItem(
        PlanAction.SkipDuplicate(duplicate.candidate.externalRef, duplicate.reason, duplicate.existing),
        PlanItemStatus.Skipped
      )
    Plan(creates ++ skips)

  // Stamp the external ref into the expense comment so a re-run recognises what this tool already booked.
  def refMarker(ref: ExternalRef): String = s"sfref:${ref.value}"

  def newExpense(ref: ExternalRef, candidate: CandidateExpense): NewExpense =
    NewExpense(
      name = candidate.name,
      amount = candidate.amount,
      created = candidate.occurredOn,
      variableSymbol = None,
      comment = Some(refMarker(ref))
    )

  private def matchesRef(candidate: CandidateExpense, expense: Expense): Boolean =
    expense.comment.exists(_.contains(candidate.externalRef.value))

  def render(plan: Plan): String =
    val header = s"Plan: ${plan.items.size} item(s)"
    (header :: plan.items.map(renderItem)).mkString("\n")

  private def renderItem(item: PlanItem): String =
    val status = item.status.toString
    item.action match
      case PlanAction.CreateExpense(_, expense, attach) =>
        val attachment = attach.fold("")(receipt => s" + ${receipt.path}")
        s"[$status] create '${expense.name}' ${expense.amount.amount} ${expense.amount.currency}$attachment"
      case PlanAction.AttachToExisting(expenseId, attachment) =>
        s"[$status] attach ${attachment.path} to expense ${expenseId.value}"
      case PlanAction.SkipDuplicate(_, reason, matched) =>
        s"[$status] skip duplicate of expense ${matched.value}: $reason"
      case PlanAction.NeedsResolution(_, candidates, reason) =>
        s"[$status] needs resolution ($reason); candidates: ${candidates.map(_.value).mkString(", ")}"

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
    ExternalRef(digest.map(byte => f"${byte & 0xff}%02x").mkString)
end ExpensePlanner
