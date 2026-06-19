package superfaktura

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class ExpensePlannerTest extends AnyFreeSpec with Matchers:

  private val date = LocalDate.of(2026, 6, 19)

  private def tx(
      direction: TransactionType,
      amount: String,
      recipientInfo: Option[String],
      description: String,
      variableSymbol: Option[String] = None,
      specificSymbol: Option[String] = None,
      iban: Option[String] = None
  ): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = direction,
      counterpartyIban = iban,
      variableSymbol = variableSymbol,
      specificSymbol = specificSymbol,
      recipientInfo = recipientInfo,
      description = description
    )

  private def refOf(transaction: Transaction): ExternalRef =
    ExpensePlanner.toCandidates(List(transaction)).head.externalRef

  "toCandidates" - {
    "drops credits and derives the name as card merchant, else recipient info, else description" in {
      val card = tx(
        direction = TransactionType.Debit,
        amount = "73.71",
        recipientInfo = Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        description = "GP NÁKUP POS"
      )
      val transfer = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = Some("UHRADA POISTNEHO"),
        description = "Platba 8180/000000-7000747747"
      )
      val fee = tx(
        direction = TransactionType.Debit,
        amount = "3.04",
        recipientInfo = None,
        description = "Transakčná daň 12.06.2026"
      )
      val credit = tx(
        direction = TransactionType.Credit,
        amount = "7850.00",
        recipientInfo = None,
        description = "INV 2026005"
      )

      ExpensePlanner.toCandidates(List(card, credit, transfer, fee)).map(_.name) shouldBe List(
        "SHELL 8203",
        "UHRADA POISTNEHO",
        "Transakčná daň 12.06.2026"
      )
    }

    "does not extract a merchant from a non-card row whose recipient info is amount-shaped" in {
      val transfer = tx(
        direction = TransactionType.Debit,
        amount = "12.34",
        recipientInfo = Some("12.34EUR FOO"),
        description = "Platba 123"
      )

      ExpensePlanner.toCandidates(List(transfer)).map(_.name) shouldBe List("12.34EUR FOO")
    }

    "derives a deterministic external ref, distinct even where a naive join would collide" in {
      val base = tx(direction = TransactionType.Debit, amount = "45.45", recipientInfo = None, description = "x")
      refOf(base) shouldBe refOf(base)

      val a = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = None,
        description = "x",
        variableSymbol = Some("1"),
        specificSymbol = Some("2|3")
      )
      val b = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = None,
        description = "x",
        variableSymbol = Some("1|2"),
        specificSymbol = Some("3")
      )

      refOf(a) should not be refOf(b)
    }
  }

  "triage" - {
    "splits candidates into ref-matched duplicates and fresh creates, preserving order" in {
      val card = tx(
        direction = TransactionType.Debit,
        amount = "73.71",
        recipientInfo = Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        description = "GP NÁKUP POS"
      )
      val transfer = tx(
        direction = TransactionType.Debit,
        amount = "45.45",
        recipientInfo = Some("UHRADA POISTNEHO"),
        description = "Platba 8180"
      )
      val candidates = ExpensePlanner.toCandidates(List(card, transfer))
      val shellRef = candidates.find(_.name == "SHELL 8203").get.externalRef
      val existing = List(
        Expense(
          id = ExpenseId(9),
          name = "SHELL",
          amount = Money(BigDecimal("73.71"), "EUR"),
          created = date,
          comment = Some(ExpensePlanner.refMarker(shellRef))
        )
      )

      val result = ExpensePlanner.triage(candidates, existing)
      result.toCreate.map(_.name) shouldBe List("UHRADA POISTNEHO")
      result.duplicates.map(_.existingId) shouldBe List(ExpenseId(9))
      result.duplicates.map(_.candidate.name) shouldBe List("SHELL 8203")
    }

    "treats an expense whose comment lacks the ref as not a duplicate" in {
      val candidates = ExpensePlanner.toCandidates(
        List(tx(direction = TransactionType.Debit, amount = "45.45", recipientInfo = None, description = "x"))
      )
      val existing = List(
        Expense(
          id = ExpenseId(1),
          name = "x",
          amount = Money(BigDecimal("45.45"), "EUR"),
          created = date,
          comment = Some("unrelated")
        )
      )

      val result = ExpensePlanner.triage(candidates, existing)
      result.toCreate should have size 1
      result.duplicates shouldBe empty
    }
  }

  "windowOf" - {
    "spans the earliest and latest candidate dates" in {
      val candidates = List(
        CandidateExpense(ExternalRef("a"), "A", Money(BigDecimal("1.00"), "EUR"), LocalDate.of(2026, 6, 10)),
        CandidateExpense(ExternalRef("b"), "B", Money(BigDecimal("2.00"), "EUR"), LocalDate.of(2026, 6, 2)),
        CandidateExpense(ExternalRef("c"), "C", Money(BigDecimal("3.00"), "EUR"), LocalDate.of(2026, 6, 20))
      )

      ExpensePlanner.windowOf(candidates) shouldBe DateWindow(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 20))
    }
  }

  "buildPlan" - {
    "emits Pending creates and Skipped duplicates" in {
      val fresh = CandidateExpense(ExternalRef("r1"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)
      val dup = CandidateExpense(ExternalRef("r2"), "SHELL", Money(BigDecimal("73.71"), "EUR"), date)

      ExpensePlanner.buildPlan(Triage(List(fresh), List(Duplicate(dup, ExpenseId(9), "dup")))) shouldBe Plan(
        List(
          PlanItem(PlanAction.CreateExpense(ExternalRef("r1"), fresh, None), PlanItemStatus.Pending),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(9)), PlanItemStatus.Skipped)
        )
      )
    }
  }

  "newExpense" - {
    "stamps the external ref into the comment so a re-run recognises it" in {
      val candidate = CandidateExpense(ExternalRef("abc"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)

      val request = ExpensePlanner.newExpense(candidate.externalRef, candidate)
      request.name shouldBe "ORANGE"
      request.amount shouldBe Money(BigDecimal("45.45"), "EUR")
      request.created shouldBe date
      request.comment shouldBe Some("sfref:abc")
    }
  }

  "render" - {
    "summarises the plan with a header and one line per item, for every action variant" in {
      val plan = Plan(
        List(
          PlanItem(
            PlanAction.CreateExpense(
              ExternalRef("r"),
              CandidateExpense(ExternalRef("r"), "SHELL 8203", Money(BigDecimal("73.71"), "EUR"), date),
              None
            ),
            PlanItemStatus.Pending
          ),
          PlanItem(PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/x.pdf")), PlanItemStatus.Applied),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("d"), "already booked", ExpenseId(7)), PlanItemStatus.Skipped),
          PlanItem(
            PlanAction.NeedsResolution(ExternalRef("n"), List(ExpenseId(1), ExpenseId(2)), "ambiguous"),
            PlanItemStatus.Pending
          )
        )
      )

      val rendered = ExpensePlanner.render(plan)
      rendered should include("Plan: 4 item(s)")
      rendered should include("create 'SHELL 8203' 73.71 EUR")
      rendered should include("attach /x.pdf to expense 42")
      rendered should include("skip duplicate of expense 7: already booked")
      rendered should include("needs resolution (ambiguous); candidates: 1, 2")
    }
  }
end ExpensePlannerTest
