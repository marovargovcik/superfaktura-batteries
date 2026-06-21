package superfaktura.matching

import superfaktura.bank.CandidateExpense

case class Triage(toCreate: List[CandidateExpense], duplicates: List[Duplicate])
