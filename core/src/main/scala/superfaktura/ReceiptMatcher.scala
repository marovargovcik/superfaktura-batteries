package superfaktura

object ReceiptMatcher:

  def matchReceipts(receipts: List[Receipt], targets: List[MatchTarget], window: MatchWindow): MatchResult =
    val targetsFor = receipts.map(receipt => receipt -> targets.filter(matches(receipt, _, window))).toMap
    val receiptsFor = targets.map(target => target -> receipts.filter(matches(_, target, window))).toMap

    def uniquePairing(receipt: Receipt): Option[Pairing] =
      targetsFor(receipt) match
        case target :: Nil if receiptsFor(target).sizeIs == 1 => Some(Pairing(receipt, target))
        case _ => None

    val paired = receipts.flatMap(uniquePairing)
    val pairedReceipts = paired.map(_.receipt).toSet
    val pairedTargets = paired.map(_.target).toSet

    val ambiguousReceipts = receipts.filterNot(pairedReceipts.contains).collect:
      case receipt if targetsFor(receipt).sizeIs > 1 => AmbiguousReceipt(receipt, targetsFor(receipt))

    val contestedTargets = targets.filterNot(pairedTargets.contains).collect:
      case target if receiptsFor(target).sizeIs > 1 => ContestedTarget(target, receiptsFor(target))

    val unmatchedReceipts = receipts.filter(receipt => targetsFor(receipt).isEmpty)
    // A target contested only through an ambiguous receipt is surfaced via that receipt, so it is
    // intentionally neither paired nor listed as unmatched here.
    val unmatchedTargets = targets.filter(target => receiptsFor(target).isEmpty)

    MatchResult(paired, ambiguousReceipts, contestedTargets, unmatchedReceipts, unmatchedTargets)

  private def matches(receipt: Receipt, target: MatchTarget, window: MatchWindow): Boolean =
    receipt.amount == target.amount &&
      !target.date.isBefore(receipt.date.minusDays(window.daysBefore)) &&
      !target.date.isAfter(receipt.date.plusDays(window.daysAfter))
end ReceiptMatcher
