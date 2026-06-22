package superfaktura.plan

import java.time.LocalDate

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import superfaktura.{DateWindow, Money}
import superfaktura.bank.{CandidateExpense, ExternalRef, Transaction, TransactionType}
import superfaktura.expense.{Expense, ExpenseId}
import superfaktura.matching.{AmbiguousReceipt, ContestedTarget, MatchResult, MatchTarget, MatchWindow, Pairing}
import superfaktura.receipt.{Receipt, ReceiptBytes, ReceiptMarker, ReceiptRef}
import superfaktura.rule.{Rule, RuleMatch, RuleSet}

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

    "applies a matching rename rule (with {date}), and leaves the external ref untouched" in {
      val rent = tx(
        direction = TransactionType.Debit,
        amount = "450.00",
        recipientInfo = Some("LANDLORD"),
        description = "Platba"
      )
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))

      val renamed = ExpensePlanner.toCandidates(List(rent), rules).head
      renamed.name shouldBe "Rent 19.06.2026"
      renamed.externalRef shouldBe refOf(rent)
    }

    "falls back to the derived name when no rule matches" in {
      val tesco =
        tx(direction = TransactionType.Debit, amount = "12.00", recipientInfo = Some("TESCO"), description = "Platba")
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent"), None)))

      ExpensePlanner.toCandidates(List(tesco), rules).head.name shouldBe "TESCO"
    }
  }

  "ruleAttachments" - {
    "maps a debit's external ref to the rule's fixed attachment path, ignoring non-attaching rules" in {
      val rent =
        tx(direction = TransactionType.Debit, amount = "450.00", recipientInfo = Some("LANDLORD"), description = "x")
      val tesco =
        tx(direction = TransactionType.Debit, amount = "12.00", recipientInfo = Some("TESCO"), description = "y")
      val rules = RuleSet(
        List(
          Rule(RuleMatch.ExactName("LANDLORD"), None, Some("/invoices/rent.pdf")),
          Rule(RuleMatch.ExactName("TESCO"), Some("Groceries"), None)
        )
      )

      ExpensePlanner.ruleAttachments(List(rent, tesco), rules) shouldBe Map(
        refOf(rent) -> ReceiptRef("/invoices/rent.pdf")
      )
    }

    "applies both the rename and the attachment when a single rule sets both" in {
      val rent =
        tx(direction = TransactionType.Debit, amount = "450.00", recipientInfo = Some("LANDLORD"), description = "x")
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), Some("/invoices/rent.pdf"))))

      val candidate = ExpensePlanner.toCandidates(List(rent), rules).head
      candidate.name shouldBe "Rent 19.06.2026"
      ExpensePlanner.ruleAttachments(List(rent), rules) shouldBe Map(
        candidate.externalRef -> ReceiptRef("/invoices/rent.pdf")
      )
    }
  }

  "ruleRenames" - {
    "maps a debit's external ref to the rendered rule name" in {
      val rent =
        tx(direction = TransactionType.Debit, amount = "450.00", recipientInfo = Some("LANDLORD"), description = "x")
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))

      ExpensePlanner.ruleRenames(List(rent), rules) shouldBe Map(refOf(rent) -> "Rent 19.06.2026")
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
      result.duplicates.map(_.existing.id) shouldBe List(ExpenseId(9))
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

    "keeps the rule-renamed name on a candidate that turns out to be a duplicate" in {
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent"), None)))
      val candidates = ExpensePlanner.toCandidates(
        List(tx(
          direction = TransactionType.Debit,
          amount = "450.00",
          recipientInfo = Some("LANDLORD"),
          description = "x"
        )),
        rules
      )
      val existing = List(
        Expense(
          id = ExpenseId(3),
          name = "anything",
          amount = Money(BigDecimal("450.00"), "EUR"),
          created = date,
          comment = Some(ExpensePlanner.refMarker(candidates.head.externalRef))
        )
      )

      ExpensePlanner.triage(candidates, existing).duplicates.map(_.candidate.name) shouldBe List("Rent")
    }
  }

  "coverageWindow" - {
    "spans the candidate dates and the buffered receipt dates together" in {
      val candidates = List(
        CandidateExpense(ExternalRef("a"), "A", Money(BigDecimal("1.00"), "EUR"), LocalDate.of(2026, 6, 10)),
        CandidateExpense(ExternalRef("b"), "B", Money(BigDecimal("2.00"), "EUR"), LocalDate.of(2026, 6, 18))
      )
      val receipts = List(
        Receipt(ReceiptRef("r.jpg"), Money(BigDecimal("1.00"), "EUR"), LocalDate.of(2026, 6, 2)),
        Receipt(ReceiptRef("s.jpg"), Money(BigDecimal("2.00"), "EUR"), LocalDate.of(2026, 6, 20))
      )

      // candidates span 6/10–6/18; receipts add 6/2−1 = 6/1 (low) and 6/20+3 = 6/23 (high).
      ExpensePlanner.coverageWindow(candidates, receipts, MatchWindow.default) shouldBe
        DateWindow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 23))
    }

    "falls back to candidate dates when there are no receipts" in {
      val candidates = List(CandidateExpense(ExternalRef("a"), "A", Money(BigDecimal("1.00"), "EUR"), date))

      ExpensePlanner.coverageWindow(candidates, Nil, MatchWindow.default) shouldBe DateWindow(date, date)
    }

    "spans only the buffered receipt dates when there are no candidates" in {
      val receipts = List(
        Receipt(ReceiptRef("r.jpg"), Money(BigDecimal("1.00"), "EUR"), LocalDate.of(2026, 6, 10)),
        Receipt(ReceiptRef("s.jpg"), Money(BigDecimal("2.00"), "EUR"), LocalDate.of(2026, 6, 20))
      )

      ExpensePlanner.coverageWindow(Nil, receipts, MatchWindow.default) shouldBe
        DateWindow(LocalDate.of(2026, 6, 9), LocalDate.of(2026, 6, 23))
    }
  }

  "buildPlan" - {
    "emits Pending creates and Skipped duplicates" in {
      val fresh = CandidateExpense(ExternalRef("r1"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)
      val dup = CandidateExpense(ExternalRef("r2"), "SHELL", Money(BigDecimal("73.71"), "EUR"), date)
      val existing = Expense(ExpenseId(9), "SHELL", Money(BigDecimal("73.71"), "EUR"), date, None)

      ExpensePlanner.buildPlan(Triage(List(fresh), List(Duplicate(dup, existing, "dup")))) shouldBe Plan(
        List(
          PlanItem(PlanAction.CreateExpense(ExternalRef("r1"), fresh, None), PlanItemStatus.Pending),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(9)), PlanItemStatus.Skipped)
        )
      )
    }

    "renames a duplicate only when a rule's name differs from the existing expense" in {
      val toRename = CandidateExpense(ExternalRef("r1"), "X", Money(BigDecimal("1.00"), "EUR"), date)
      val alreadyNamed = CandidateExpense(ExternalRef("r2"), "Y", Money(BigDecimal("2.00"), "EUR"), date)
      val noRule = CandidateExpense(ExternalRef("r3"), "Z", Money(BigDecimal("3.00"), "EUR"), date)
      val e1 = Expense(ExpenseId(1), "OLD", Money(BigDecimal("1.00"), "EUR"), date, None)
      val e2 = Expense(ExpenseId(2), "Rent", Money(BigDecimal("2.00"), "EUR"), date, None)
      val e3 = Expense(ExpenseId(3), "Z", Money(BigDecimal("3.00"), "EUR"), date, None)
      val triage = Triage(
        Nil,
        List(Duplicate(toRename, e1, "dup"), Duplicate(alreadyNamed, e2, "dup"), Duplicate(noRule, e3, "dup"))
      )
      val renames = Map(ExternalRef("r1") -> "Rent", ExternalRef("r2") -> "Rent")

      ExpensePlanner.buildPlan(triage, MatchResult.empty, Nil, Map.empty, renames).items shouldBe List(
        PlanItem(PlanAction.RenameExpense(ExpenseId(1), "Rent"), PlanItemStatus.Pending),
        PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(2)), PlanItemStatus.Skipped),
        PlanItem(PlanAction.SkipDuplicate(ExternalRef("r3"), "dup", ExpenseId(3)), PlanItemStatus.Skipped)
      )
    }

    "attaches a paired receipt to its create, and flags an unmatched receipt" in {
      val fresh = CandidateExpense(ExternalRef("r1"), "ORANGE", Money(BigDecimal("45.45"), "EUR"), date)
      val paired = Receipt(ReceiptRef("orange.jpg"), Money(BigDecimal("45.45"), "EUR"), date)
      val orphan = Receipt(ReceiptRef("orphan.png"), Money(BigDecimal("9.99"), "EUR"), date)
      val matched = MatchResult(
        paired = List(Pairing(paired, MatchTarget.Candidate(fresh))),
        ambiguousReceipts = Nil,
        contestedTargets = Nil,
        unmatchedReceipts = List(orphan),
        unmatchedTargets = Nil
      )

      ExpensePlanner.buildPlan(Triage(List(fresh), Nil), matched, List(ReceiptRef("blurry.pdf"))).items shouldBe List(
        PlanItem(
          PlanAction.CreateExpense(ExternalRef("r1"), fresh, Some(ReceiptRef("orange.jpg"))),
          PlanItemStatus.Pending
        ),
        PlanItem(
          PlanAction.FlagReceipt(ReceiptRef("orphan.png"), "no transaction matches its amount and date"),
          PlanItemStatus.Skipped
        ),
        PlanItem(
          PlanAction.FlagReceipt(ReceiptRef("blurry.pdf"), "could not read the amount and date"),
          PlanItemStatus.Skipped
        )
      )
    }

    "lets a rule attachment override an OCR-paired receipt for the same candidate" in {
      val fresh = CandidateExpense(ExternalRef("r1"), "Rent", Money(BigDecimal("450.00"), "EUR"), date)
      val ocrPaired = Receipt(ReceiptRef("scanned.jpg"), Money(BigDecimal("450.00"), "EUR"), date)
      val matched = MatchResult(
        paired = List(Pairing(ocrPaired, MatchTarget.Candidate(fresh))),
        ambiguousReceipts = Nil,
        contestedTargets = Nil,
        unmatchedReceipts = Nil,
        unmatchedTargets = Nil
      )

      val plan = ExpensePlanner.buildPlan(
        Triage(List(fresh), Nil),
        matched,
        Nil,
        Map(ExternalRef("r1") -> ReceiptRef("/invoices/rent.pdf"))
      )
      plan.items.collect { case PlanItem(PlanAction.CreateExpense(_, _, attach), _) => attach } shouldBe
        List(Some(ReceiptRef("/invoices/rent.pdf")))
    }

    "flags ambiguous and contested receipts, deduping a ref that overlaps buckets" in {
      val c1 = CandidateExpense(ExternalRef("c1"), "A", Money(BigDecimal("1.00"), "EUR"), date)
      val c2 = CandidateExpense(ExternalRef("c2"), "B", Money(BigDecimal("1.00"), "EUR"), date)
      val contested = CandidateExpense(ExternalRef("cc"), "C", Money(BigDecimal("2.00"), "EUR"), date)
      val ambiguousReceipt = Receipt(ReceiptRef("amb.jpg"), Money(BigDecimal("1.00"), "EUR"), date)
      val r1 = Receipt(ReceiptRef("c1.jpg"), Money(BigDecimal("2.00"), "EUR"), date)
      val r2 = Receipt(ReceiptRef("c2.jpg"), Money(BigDecimal("2.00"), "EUR"), date)
      val matched = MatchResult(
        paired = Nil,
        ambiguousReceipts =
          List(AmbiguousReceipt(ambiguousReceipt, List(MatchTarget.Candidate(c1), MatchTarget.Candidate(c2)))),
        contestedTargets = List(ContestedTarget(MatchTarget.Candidate(contested), List(r1, r2))),
        unmatchedReceipts = Nil,
        unmatchedTargets = Nil
      )

      // amb.jpg is also passed as unreadable, so distinctBy must collapse it to a single flag (ambiguous wins).
      val flags = ExpensePlanner.buildPlan(Triage(Nil, Nil), matched, List(ReceiptRef("amb.jpg"))).items.collect {
        case PlanItem(PlanAction.FlagReceipt(ref, reason), _) => ref -> reason
      }
      flags.map { case (ref, _) => ref } should contain theSameElementsAs
        List(ReceiptRef("amb.jpg"), ReceiptRef("c1.jpg"), ReceiptRef("c2.jpg"))
      flags.count { case (ref, _) => ref == ReceiptRef("amb.jpg") } shouldBe 1
      flags.collectFirst { case (ref, reason) if ref == ReceiptRef("amb.jpg") => reason }.get should
        include("2 transactions")
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

  "receiptMarker" - {
    "hashes the bytes, so identical content yields the same marker and different content does not" in {
      val a = ReceiptBytes(ByteVector(1, 2, 3))
      val b = ReceiptBytes(ByteVector(1, 2, 3))
      val c = ReceiptBytes(ByteVector(9, 9, 9))

      ExpensePlanner.receiptMarker(a) shouldBe ExpensePlanner.receiptMarker(b)
      ExpensePlanner.receiptMarker(a).value should startWith("sfrcpt:")
      ExpensePlanner.receiptMarker(a) should not be ExpensePlanner.receiptMarker(c)
    }
  }

  "appendMarker" - {
    "adds the marker to an existing comment but never duplicates it" in {
      ExpensePlanner.appendMarker(None, ReceiptMarker("sfrcpt:x")) shouldBe Some("sfrcpt:x")
      ExpensePlanner.appendMarker(Some("sfref:r"), ReceiptMarker("sfrcpt:x")) shouldBe Some("sfref:r sfrcpt:x")
      ExpensePlanner.appendMarker(Some("sfref:r sfrcpt:x"), ReceiptMarker("sfrcpt:x")) shouldBe Some("sfref:r sfrcpt:x")
    }

    "matches a recorded marker by whole token, not substring" in {
      ExpensePlanner.appendMarker(Some("sfrcpt:xyz"), ReceiptMarker("sfrcpt:x")) shouldBe Some("sfrcpt:xyz sfrcpt:x")
    }
  }

  "receiptMarkers" - {
    "extracts only the sfrcpt: tokens, ignoring the ref marker and any other text" in {
      ExpensePlanner.receiptMarkers(Some("sfref:r sfrcpt:a sfrcpt:b booked")) shouldBe
        Set(ReceiptMarker("sfrcpt:a"), ReceiptMarker("sfrcpt:b"))
      ExpensePlanner.receiptMarkers(Some("sfref:r")) shouldBe Set.empty
      ExpensePlanner.receiptMarkers(None) shouldBe Set.empty
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
          PlanItem(PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/x.pdf"), None), PlanItemStatus.Applied),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("d"), "already booked", ExpenseId(7)), PlanItemStatus.Skipped),
          PlanItem(PlanAction.RenameExpense(ExpenseId(8), "Rent 16.06.2026"), PlanItemStatus.Pending),
          PlanItem(
            PlanAction.NeedsResolution(ExternalRef("n"), List(ExpenseId(1), ExpenseId(2)), "ambiguous"),
            PlanItemStatus.Pending
          ),
          PlanItem(PlanAction.FlagReceipt(ReceiptRef("/y.png"), "no match"), PlanItemStatus.Skipped),
          PlanItem(PlanAction.ReceiptAlreadyUploaded(ReceiptRef("/z.pdf"), ExpenseId(99)), PlanItemStatus.Skipped)
        )
      )

      val rendered = ExpensePlanner.render(plan)
      rendered should include("Plan: 7 item(s)")
      rendered should include("create 'SHELL 8203' 73.71 EUR")
      rendered should include("attach /x.pdf to expense 42")
      rendered should include("skip duplicate of expense 7: already booked")
      rendered should include("rename expense 8 to 'Rent 16.06.2026'")
      rendered should include("needs resolution (ambiguous); candidates: 1, 2")
      rendered should include("flag receipt /y.png: no match")
      rendered should include("skip /z.pdf: already uploaded to expense 99")
    }
  }
end ExpensePlannerTest
