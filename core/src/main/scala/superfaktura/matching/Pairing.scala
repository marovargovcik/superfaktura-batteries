package superfaktura.matching

import superfaktura.receipt.Receipt

case class Pairing(receipt: Receipt, target: MatchTarget)
