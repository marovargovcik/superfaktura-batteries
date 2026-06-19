package superfaktura

object ReceiptMatcher:

  def matchReceipts(receipts: List[Receipt], candidates: List[CandidateExpense], window: MatchWindow): MatchResult =
    val candidatesFor = receipts.map(receipt => receipt -> candidates.filter(matches(receipt, _, window))).toMap
    val receiptsFor = candidates.map(candidate => candidate -> receipts.filter(matches(_, candidate, window))).toMap

    def uniquePairing(receipt: Receipt): Option[Pairing] =
      candidatesFor(receipt) match
        case candidate :: Nil if receiptsFor(candidate).sizeIs == 1 => Some(Pairing(receipt, candidate))
        case _ => None

    val paired = receipts.flatMap(uniquePairing)
    val pairedReceipts = paired.map(_.receipt).toSet

    val (ambiguous, unmatchedReceipts) = receipts
      .filterNot(pairedReceipts.contains)
      .partitionMap: receipt =>
        candidatesFor(receipt) match
          case Nil => Right(receipt)
          case options => Left(AmbiguousReceipt(receipt, options))

    val unmatchedCandidates = candidates.filter(candidate => receiptsFor(candidate).isEmpty)

    MatchResult(paired, ambiguous, unmatchedReceipts, unmatchedCandidates)

  private def matches(receipt: Receipt, candidate: CandidateExpense, window: MatchWindow): Boolean =
    receipt.amount == candidate.amount &&
      !candidate.occurredOn.isBefore(receipt.date.minusDays(window.daysBefore)) &&
      !candidate.occurredOn.isAfter(receipt.date.plusDays(window.daysAfter))
