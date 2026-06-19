package superfaktura

import java.time.LocalDate

case class CandidateExpense(externalRef: ExternalRef, name: String, amount: Money, occurredOn: LocalDate)
