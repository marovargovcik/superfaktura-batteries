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

  private def debit(
      amount: String,
      recipientInfo: Option[String] = None,
      description: String = "Platba",
      variableSymbol: Option[String] = None,
      specificSymbol: Option[String] = None,
      iban: Option[String] = None
  ): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = TransactionType.Debit,
      counterpartyIban = iban,
      variableSymbol = variableSymbol,
      specificSymbol = specificSymbol,
      recipientInfo = recipientInfo,
      description = description
    )

  private def credit(amount: String, description: String): Transaction =
    Transaction(
      date = date,
      amount = Money(BigDecimal(amount), "EUR"),
      direction = TransactionType.Credit,
      counterpartyIban = None,
      variableSymbol = None,
      specificSymbol = None,
      recipientInfo = None,
      description = description
    )

  private def candidate(ref: String, name: String, amount: String, on: LocalDate = date): CandidateExpense =
    CandidateExpense(ExternalRef(ref), name, Money(BigDecimal(amount), "EUR"), on)

  private def expense(id: Long, name: String, amount: String, comment: Option[String] = None): Expense =
    Expense(ExpenseId(id), name, Money(BigDecimal(amount), "EUR"), date, comment)

  private def receipt(ref: String, amount: String, on: LocalDate = date): Receipt =
    Receipt(ReceiptRef(ref), Money(BigDecimal(amount), "EUR"), on)

  private def refOf(transaction: Transaction): ExternalRef =
    ExpensePlanner.toCandidates(List(transaction)).head.externalRef

  "toCandidates" - {
    "drops credits and derives the name as card merchant, else recipient info, else description" in {
      val card = debit(
        "73.71",
        Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        "GP NÁKUP POS"
      )
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"), "Platba 8180/000000-7000747747")
      val fee = debit("3.04", description = "Transakčná daň 12.06.2026")
      val income = credit("7850.00", "INV 2026005")

      ExpensePlanner.toCandidates(List(card, income, transfer, fee)).map(_.name) shouldBe List(
        "SHELL 8203",
        "UHRADA POISTNEHO",
        "Transakčná daň 12.06.2026"
      )
    }

    "does not extract a merchant from a non-card row whose recipient info is amount-shaped" in {
      ExpensePlanner.toCandidates(List(debit("12.34", Some("12.34EUR FOO")))).map(_.name) shouldBe List("12.34EUR FOO")
    }

    "derives a deterministic external ref, distinct even where a naive join would collide" in {
      val base = debit("45.45", description = "x")
      refOf(base) shouldBe refOf(base)

      val a = debit("45.45", description = "x", variableSymbol = Some("1"), specificSymbol = Some("2|3"))
      val b = debit("45.45", description = "x", variableSymbol = Some("1|2"), specificSymbol = Some("3"))

      refOf(a) should not be refOf(b)
    }

    "applies a matching rename rule (with {date}), and leaves the external ref untouched" in {
      val rent = debit("450.00", Some("LANDLORD"))
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))

      val renamed = ExpensePlanner.toCandidates(List(rent), rules).head
      renamed.name shouldBe "Rent 19.06.2026"
      renamed.externalRef shouldBe refOf(rent)
    }

    "falls back to the derived name when no rule matches" in {
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent"), None)))

      ExpensePlanner.toCandidates(List(debit("12.00", Some("TESCO"))), rules).head.name shouldBe "TESCO"
    }
  }

  "ruleAttachments" - {
    "maps a debit's external ref to the rule's fixed attachment path, ignoring non-attaching rules" in {
      val rent = debit("450.00", Some("LANDLORD"))
      val tesco = debit("12.00", Some("TESCO"))
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
      val rent = debit("450.00", Some("LANDLORD"))
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), Some("/invoices/rent.pdf"))))

      val renamed = ExpensePlanner.toCandidates(List(rent), rules).head
      renamed.name shouldBe "Rent 19.06.2026"
      ExpensePlanner.ruleAttachments(List(rent), rules) shouldBe Map(
        renamed.externalRef -> ReceiptRef("/invoices/rent.pdf")
      )
    }
  }

  "ruleRenames" - {
    "maps a debit's external ref to the rendered rule name" in {
      val rent = debit("450.00", Some("LANDLORD"))
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent {date}"), None)))

      ExpensePlanner.ruleRenames(List(rent), rules) shouldBe Map(refOf(rent) -> "Rent 19.06.2026")
    }
  }

  "triage" - {
    "splits candidates into ref-matched duplicates and fresh creates, preserving order" in {
      val card = debit(
        "73.71",
        Some("423473******7299 BRATISLAVSKA MAREK VARGOVČÍK 20260613 16:13:59 73.71EUR SHELL 8203"),
        "GP NÁKUP POS"
      )
      val transfer = debit("45.45", Some("UHRADA POISTNEHO"))
      val candidates = ExpensePlanner.toCandidates(List(card, transfer))
      val shellRef = candidates.find(_.name == "SHELL 8203").get.externalRef
      val existing = List(expense(9, "SHELL", "73.71", comment = Some(ExpensePlanner.refMarker(shellRef))))

      val result = ExpensePlanner.triage(candidates, existing)
      result.toCreate.map(_.name) shouldBe List("UHRADA POISTNEHO")
      result.duplicates.map(_.existing.id) shouldBe List(ExpenseId(9))
      result.duplicates.map(_.candidate.name) shouldBe List("SHELL 8203")
    }

    "treats an expense whose comment lacks the ref as not a duplicate" in {
      val candidates = ExpensePlanner.toCandidates(List(debit("45.45", description = "x")))
      val existing = List(expense(1, "x", "45.45", comment = Some("unrelated")))

      val result = ExpensePlanner.triage(candidates, existing)
      result.toCreate should have size 1
      result.duplicates shouldBe empty
    }

    "keeps the rule-renamed name on a candidate that turns out to be a duplicate" in {
      val rules = RuleSet(List(Rule(RuleMatch.ExactName("LANDLORD"), Some("Rent"), None)))
      val candidates = ExpensePlanner.toCandidates(List(debit("450.00", Some("LANDLORD"))), rules)
      val existing =
        List(expense(3, "anything", "450.00", comment = Some(ExpensePlanner.refMarker(candidates.head.externalRef))))

      ExpensePlanner.triage(candidates, existing).duplicates.map(_.candidate.name) shouldBe List("Rent")
    }
  }

  "coverageWindow" - {
    "spans the candidate dates and the buffered receipt dates together" in {
      val candidates = List(
        candidate("a", "A", "1.00", LocalDate.of(2026, 6, 10)),
        candidate("b", "B", "2.00", LocalDate.of(2026, 6, 18))
      )
      val receipts =
        List(receipt("r.jpg", "1.00", LocalDate.of(2026, 6, 2)), receipt("s.jpg", "2.00", LocalDate.of(2026, 6, 20)))

      // candidates span 6/10–6/18; receipts add 6/2−1 = 6/1 (low) and 6/20+3 = 6/23 (high).
      ExpensePlanner.coverageWindow(candidates, receipts, MatchWindow.default) shouldBe
        DateWindow(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 23))
    }

    "falls back to candidate dates when there are no receipts" in {
      ExpensePlanner.coverageWindow(List(candidate("a", "A", "1.00")), Nil, MatchWindow.default) shouldBe
        DateWindow(date, date)
    }

    "spans only the buffered receipt dates when there are no candidates" in {
      val receipts =
        List(receipt("r.jpg", "1.00", LocalDate.of(2026, 6, 10)), receipt("s.jpg", "2.00", LocalDate.of(2026, 6, 20)))

      ExpensePlanner.coverageWindow(Nil, receipts, MatchWindow.default) shouldBe
        DateWindow(LocalDate.of(2026, 6, 9), LocalDate.of(2026, 6, 23))
    }
  }

  "buildPlan" - {
    "emits Pending creates and Skipped duplicates" in {
      val fresh = candidate("r1", "ORANGE", "45.45")
      val dup = candidate("r2", "SHELL", "73.71")

      ExpensePlanner.buildPlan(
        Triage(List(fresh), List(Duplicate(dup, expense(9, "SHELL", "73.71"), "dup")))
      ) shouldBe Plan(
        List(
          PlanItem(PlanAction.CreateExpense(ExternalRef("r1"), fresh, None), PlanItemStatus.Pending),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(9)), PlanItemStatus.Skipped)
        )
      )
    }

    "renames a duplicate only when a rule's name differs from the existing expense" in {
      val toRename = candidate("r1", "X", "1.00")
      val alreadyNamed = candidate("r2", "Y", "2.00")
      val noRule = candidate("r3", "Z", "3.00")
      val triage = Triage(
        Nil,
        List(
          Duplicate(toRename, expense(1, "OLD", "1.00"), "dup"),
          Duplicate(alreadyNamed, expense(2, "Rent", "2.00"), "dup"),
          Duplicate(noRule, expense(3, "Z", "3.00"), "dup")
        )
      )
      val renames = Map(ExternalRef("r1") -> "Rent", ExternalRef("r2") -> "Rent")

      ExpensePlanner.buildPlan(triage, MatchResult.empty, Nil, Map.empty, renames).items shouldBe List(
        PlanItem(PlanAction.RenameExpense(ExpenseId(1), "Rent"), PlanItemStatus.Pending),
        PlanItem(PlanAction.SkipDuplicate(ExternalRef("r2"), "dup", ExpenseId(2)), PlanItemStatus.Skipped),
        PlanItem(PlanAction.SkipDuplicate(ExternalRef("r3"), "dup", ExpenseId(3)), PlanItemStatus.Skipped)
      )
    }

    "attaches a paired receipt to its create, and flags an unmatched receipt" in {
      val fresh = candidate("r1", "ORANGE", "45.45")
      val paired = receipt("orange.jpg", "45.45")
      val orphan = receipt("orphan.png", "9.99")
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
      val fresh = candidate("r1", "Rent", "450.00")
      val ocrPaired = receipt("scanned.jpg", "450.00")
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
      val c1 = candidate("c1", "A", "1.00")
      val c2 = candidate("c2", "B", "1.00")
      val contested = candidate("cc", "C", "2.00")
      val ambiguousReceipt = receipt("amb.jpg", "1.00")
      val r1 = receipt("c1.jpg", "2.00")
      val r2 = receipt("c2.jpg", "2.00")
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
      val orange = candidate("abc", "ORANGE", "45.45")

      val request = ExpensePlanner.newExpense(orange.externalRef, orange)
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
            PlanAction.CreateExpense(ExternalRef("r"), candidate("r", "SHELL 8203", "73.71"), None),
            PlanItemStatus.Pending
          ),
          PlanItem(PlanAction.AttachToExisting(ExpenseId(42), ReceiptRef("/x.pdf"), None), PlanItemStatus.Applied),
          PlanItem(PlanAction.SkipDuplicate(ExternalRef("d"), "already booked", ExpenseId(7)), PlanItemStatus.Skipped),
          PlanItem(PlanAction.RenameExpense(ExpenseId(8), "Rent 16.06.2026"), PlanItemStatus.Pending),
          PlanItem(PlanAction.FlagReceipt(ReceiptRef("/y.png"), "no match"), PlanItemStatus.Skipped),
          PlanItem(PlanAction.ReceiptAlreadyUploaded(ReceiptRef("/z.pdf"), ExpenseId(99)), PlanItemStatus.Skipped)
        )
      )

      val rendered = ExpensePlanner.render(plan)
      rendered should include("Plan: 6 item(s)")
      rendered should include("create 'SHELL 8203' 73.71 EUR")
      rendered should include("attach /x.pdf to expense 42")
      rendered should include("skip duplicate of expense 7: already booked")
      rendered should include("rename expense 8 to 'Rent 16.06.2026'")
      rendered should include("flag receipt /y.png: no match")
      rendered should include("skip /z.pdf: already uploaded to expense 99")
    }
  }
end ExpensePlannerTest
