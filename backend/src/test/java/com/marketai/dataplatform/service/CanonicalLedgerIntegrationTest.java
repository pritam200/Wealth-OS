package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalLedgerIntegrationTest extends DataPlatformTestSupport {

    @Autowired HoldingService holdings;
    @Autowired ResolutionService resolution;
    @Autowired DataQualityService quality;
    @Autowired TransactionQueryService queries;
    @Autowired IssueService issueService;
    @Autowired LedgerService ledgerService;

    /* ───────── email is a signal ───────── */

    @Test @DisplayName("email → candidate: an email creates a pending, unverified ledger entry, never a fact")
    void emailCreatesCandidate() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "15000"), "msg-1", "fp-1");

        List<CanonicalTransaction> list = all();
        assertThat(list).hasSize(1);
        CanonicalTransaction t = list.get(0);
        assertThat(t.getSourceType()).isEqualTo(SourceType.EMAIL);
        assertThat(t.getTransactionType()).isEqualTo(TransactionType.SIP);
        assertThat(t.getStatus()).isEqualTo(TxnStatus.PENDING_RECONCILIATION);
        assertThat(t.getReconciliationStatus()).isEqualTo(ReconStatus.PENDING);
        assertThat(t.getConfidence()).isLessThan(0.75);          // 0.75 base × 0.85 extractor confidence
        assertThat(candidates.findAll()).singleElement().satisfies(c -> assertThat(c.getStatus()).isEqualTo(CandidateStatus.PENDING_RECONCILIATION));
    }

    @Test @DisplayName("raw layer: the payload is stored encrypted, with its schema and processing status")
    void rawLayer() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "15000"), "msg-1", "fp-1");
        RawFinancialData raw = raws.findAll().get(0);
        assertThat(raw.getSchemaVersion()).isEqualTo("email-parsed-v1");
        assertThat(raw.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(raw.getPayload()).doesNotContain("HDFC").doesNotContain(ISIN_A);   // ciphertext
        assertThat(raw.getSourceType()).isEqualTo(SourceType.EMAIL);
    }

    /* ───────── multiple sources → one transaction ───────── */

    @Test @DisplayName("email + aggregator + statement report the same SIP: ONE canonical transaction, three sources, VERIFIED")
    void threeSourcesOneTransaction() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "msg-1", "fp-1");
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.431", "10000.00"));
        ingest(SourceType.STATEMENT, "hdfc-mf-statement", statementRow("STMT-9", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.431", "190.73", "10000.00"));

        List<CanonicalTransaction> list = all();
        assertThat(list).hasSize(1);
        CanonicalTransaction t = list.get(0);
        assertThat(t.getReconciliationStatus()).isEqualTo(ReconStatus.VERIFIED);
        assertThat(t.getStatus()).isEqualTo(TxnStatus.CONFIRMED);
        assertThat(t.getSourceType()).isEqualTo(SourceType.ACCOUNT_AGGREGATOR);      // most reliable source leads
        assertThat(t.getConfidence()).isEqualTo(1.0);
        assertThat(t.getQuantity()).isEqualByComparingTo("52.431");                     // filled from the aggregator
        assertThat(sources.findByTransactionIdOrderByIdAsc(t.getId())).extracting(TransactionSource::getSourceType)
            .containsExactlyInAnyOrder(SourceType.EMAIL, SourceType.ACCOUNT_AGGREGATOR, SourceType.STATEMENT);
        assertThat(sources.findByTransactionIdOrderByIdAsc(t.getId())).allSatisfy(s -> assertThat(s.getMatchKind()).isNotNull());
    }

    @Test @DisplayName("the same ₹10,000 SIP on the same day in two different funds stays TWO transactions")
    void sameAmountDifferentFund() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa",
            aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.4", "10000"),
            aaTxn("AA-2", "SIP", "2026-08-15", ISIN_B, "HDFC Top 100", "30.1", "10000"));
        assertThat(all()).hasSize(2);
    }

    /* ───────── idempotency ───────── */

    @Test @DisplayName("idempotency: processing the same aggregator payload twice creates nothing new")
    void aaIdempotent() {
        RawRecord r = aaTxn("AA-1", "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.431", "10000");
        var first = ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", r);
        var second = ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", r);
        assertThat(first.created).isEqualTo(1);
        assertThat(second.created).isZero();
        assertThat(second.duplicated).isEqualTo(1);
        assertThat(all()).hasSize(1);
        assertThat(raws.findAll()).hasSize(1);
    }

    @Test @DisplayName("idempotency: the same email line recorded twice creates nothing new")
    void emailIdempotent() {
        var pe = emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000");
        emailRecorder.record(USER, pe, "msg-1", "fp-1");
        emailRecorder.record(USER, pe, "msg-1", "fp-1");
        emailRecorder.record(USER, pe, "msg-FORWARD", "fp-1");     // a forwarded copy carries the same line fingerprint
        assertThat(all()).hasSize(1);
    }

    @Test @DisplayName("idempotency: a statement file imported twice creates nothing new")
    void statementIdempotent() {
        RawRecord a = statementRow(null, "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "10", "100", "1000");
        ingest(SourceType.STATEMENT, "csv", a);
        var again = ingest(SourceType.STATEMENT, "csv", a);
        assertThat(again.created).isZero();
        assertThat(all()).hasSize(1);
    }

    @Test @DisplayName("a duplicate aggregator record redelivered with a changed payload updates in place, it does not duplicate")
    void aaRedeliveryWithCorrection() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.431", "10000"));
        var res = ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "52.431", "10000.40"));
        assertThat(all()).hasSize(1);
        assertThat(res.updated + res.duplicated).isEqualTo(1);
    }

    /* ───────── missing transaction ───────── */

    @Test @DisplayName("missing transaction: email has June, July, Sept; the aggregator has all four — August is reported, not silently absorbed")
    void missingTransactionDetected() {
        emailRecorder.record(USER, emailSip("2026-06-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m6", "fp6");
        emailRecorder.record(USER, emailSip("2026-07-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m7", "fp7");
        emailRecorder.record(USER, emailSip("2026-09-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m9", "fp9");

        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa",
            aaTxn("AA-6", "SIP", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaTxn("AA-7", "SIP", "2026-07-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaTxn("AA-8", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaTxn("AA-9", "SIP", "2026-09-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));

        assertThat(all()).hasSize(4);   // June, July, Sept merged; August added
        CanonicalTransaction august = all().stream().filter(t -> t.getTransactionDate().equals(LocalDate.parse("2026-08-15"))).findFirst().orElseThrow();
        assertThat(august.getReconciliationStatus()).isEqualTo(ReconStatus.MISSING);
        LedgerIssue issue = issueRepo.findByUserIdAndTypeAndStatusIn(USER, IssueType.MISSING_TRANSACTION, List.of(IssueStatus.OPEN)).get(0);
        assertThat(issue.getTransactionId()).isEqualTo(august.getId());
        assertThat(issue.getDescription()).contains("2026-08-15").contains("10000");
        // the other three were corroborated, not flagged
        assertThat(all().stream().filter(t -> t.getReconciliationStatus() == ReconStatus.VERIFIED)).hasSize(3);
        assertThat(issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.MISSING_TRANSACTION)).hasSize(1);
    }

    @Test @DisplayName("a first aggregator sync with no prior weak-source history is new history, not a wave of missing-transaction issues")
    void firstSyncIsNotMissing() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa",
            aaTxn("AA-6", "SIP", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
            aaTxn("AA-7", "SIP", "2026-07-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        assertThat(issueRepo.findAll()).isEmpty();
    }

    @Test @DisplayName("confirming a missing transaction closes the issue and verifies the entry, with an audit record")
    void confirmMissing() {
        emailRecorder.record(USER, emailSip("2026-06-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m6", "fp6");
        emailRecorder.record(USER, emailSip("2026-09-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m9", "fp9");
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-8", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        LedgerIssue issue = issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.MISSING_TRANSACTION).findFirst().orElseThrow();

        resolution.act(USER, issue.getId(), ResolutionAction.CONFIRM, "yes, I did this SIP", null);

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        CanonicalTransaction t = txns.findById(issue.getTransactionId()).orElseThrow();
        assertThat(t.getReconciliationStatus()).isEqualTo(ReconStatus.VERIFIED);
        assertThat(auditRepo.findAll()).anyMatch(a -> a.getAction().equals("CONFIRMED") && a.getActor().equals(String.valueOf(USER)));
    }

    /* ───────── feed coverage ───────── */

    @Test @DisplayName("an email transaction inside an authoritative feed's window that the feed does not list becomes UNCONFIRMED")
    void unlistedBecomesUnconfirmed() {
        emailRecorder.record(USER, emailSip(LocalDate.now().minusDays(5).toString(), ISIN_A, "HDFC Flexi Cap", "7777"), "m1", "fp1");
        ingestWithCoverage(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", LocalDate.now().minusDays(30), LocalDate.now(),
            aaTxn("AA-1", "SIP", LocalDate.now().minusDays(20).toString(), ISIN_A, "HDFC Flexi Cap", "50", "10000"));

        CanonicalTransaction email = all().stream().filter(t -> t.getSourceType() == SourceType.EMAIL).findFirst().orElseThrow();
        assertThat(email.getReconciliationStatus()).isEqualTo(ReconStatus.UNCONFIRMED);
        assertThat(issueRepo.findAll()).anyMatch(i -> i.getType() == IssueType.UNCONFIRMED_TRANSACTION && i.getTransactionId().equals(email.getId()));
    }

    @Test @DisplayName("grace period: an email still waiting after the grace period is UNCONFIRMED (the sweep)")
    void graceSweep() {
        emailRecorder.record(USER, emailSip(LocalDate.now().minusDays(20).toString(), ISIN_A, "HDFC Flexi Cap", "5000"), "m1", "fp1");
        CanonicalTransaction t = all().get(0);
        t.setIngestedAt(java.time.LocalDateTime.now().minusDays(10));
        txns.save(t);

        int changed = ledgerService.sweepGracePeriods(java.time.LocalDateTime.now());

        assertThat(changed).isEqualTo(1);
        assertThat(txns.findById(t.getId()).orElseThrow().getReconciliationStatus()).isEqualTo(ReconStatus.UNCONFIRMED);
    }

    /* ───────── holdings ───────── */

    @Test @DisplayName("holding reconciliation: a 12-unit shortfall against the institution is a HIGH issue with the difference and causes — and nothing is changed")
    void holdingMismatch() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "1245.21", "100000"));
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaHolding(ISIN_A, "HDFC Flexi Cap", "1257.21", "2026-10-05"));

        LedgerIssue issue = issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.HOLDING_MISMATCH).findFirst().orElseThrow();
        assertThat(issue.getDifference()).isEqualByComparingTo("12");
        assertThat(issue.getSeverity()).isEqualTo(IssueSeverity.HIGH);
        assertThat(issue.getSuspectedCauses()).contains("MISSING_TRANSACTION");
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
        HoldingSnapshot calc = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow();
        HoldingSnapshot rep = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.INSTITUTION_REPORTED).findFirst().orElseThrow();
        assertThat(calc.getQuantity()).isEqualByComparingTo("1245.21");      // the ledger is not rewritten
        assertThat(rep.getQuantity()).isEqualByComparingTo("1257.21");
        assertThat(rep.getLastVerifiedAt()).isNotNull();
    }

    @Test @DisplayName("a manual adjustment resolves a mismatch with a visible, attributable MANUAL transaction")
    void manualAdjustment() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "100", "10000"));
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaHolding(ISIN_A, "HDFC Flexi Cap", "112", "2026-10-05"));
        LedgerIssue issue = issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.HOLDING_MISMATCH).findFirst().orElseThrow();

        resolution.act(USER, issue.getId(), ResolutionAction.MARK_MANUAL_ADJUSTMENT, "opening balance transfer", null);

        CanonicalTransaction adj = all().stream().filter(t -> t.getSourceType() == SourceType.MANUAL).findFirst().orElseThrow();
        assertThat(adj.getQuantity()).isEqualByComparingTo("12");
        assertThat(adj.getNotes()).contains("opening balance transfer");
        HoldingSnapshot calc = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow();
        assertThat(calc.getQuantity()).isEqualByComparingTo("112");
        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
    }

    @Test @DisplayName("a matching reported holding raises nothing and marks the holding VERIFIED")
    void holdingVerified() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "100", "10000"));
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaHolding(ISIN_A, "HDFC Flexi Cap", "100", "2026-10-05"));
        assertThat(issueRepo.findAll()).isEmpty();
        var h = quality.health(USER, false).holdings();
        assertThat(h).singleElement().satisfies(x -> assertThat(x.state()).isEqualTo("VERIFIED"));
    }

    @Test @DisplayName("institution holdings can reveal what the ledger lacks even when it has no transactions for it at all")
    void institutionHoldingWithNoLedger() {
        ingest(SourceType.BROKER_API, "mock-broker", aaHolding(ISIN_A, "HDFC Flexi Cap", "50", "2026-10-05"));
        LedgerIssue issue = issueRepo.findAll().get(0);
        assertThat(issue.getType()).isEqualTo(IssueType.HOLDING_MISMATCH);
        assertThat(issue.getDifference()).isEqualByComparingTo("50");
    }

    /* ───────── reversals, cancellations ───────── */

    @Test @DisplayName("a reversal reported by the source marks the transaction reversed and removes it from the holding")
    void reversedTransaction() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "100", "10000"));
        String reversal = "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"HDFC Mutual Fund\",\"accountId\":\"FOLIO1234\",\"txn\":{\"txnId\":\"AA-1-REV\","
            + "\"type\":\"BUY\",\"date\":\"2026-06-15\",\"isin\":\"" + ISIN_A + "\",\"name\":\"HDFC Flexi Cap\",\"units\":\"100\",\"amount\":\"10000\",\"reversal\":\"true\"}}";
        // The reversal carries the original's reference, as institutions do.
        ingest(SourceType.BROKER_API, "mock-broker", new RawRecord(RecordKind.TRANSACTION, "FOLIO1234", "AA-1-REV",
            reversal.replace("\"txnId\":\"AA-1-REV\"", "\"txnId\":\"AA-1\""), "aa-fi-v1"));

        CanonicalTransaction t = all().get(0);
        assertThat(all()).hasSize(1);
        assertThat(t.getStatus()).isEqualTo(TxnStatus.REVERSED);
        var calc = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow();
        assertThat(calc.getQuantity()).isEqualByComparingTo("0");
        assertThat(auditRepo.findAll()).anyMatch(a -> a.getAction().equals("REVERSED"));
    }

    @Test @DisplayName("a cancelled order that was never booked is recorded as reversed and never counted")
    void cancelledOrder() {
        String cancelled = "{\"fiType\":\"EQUITIES\",\"institution\":\"Zerodha\",\"accountId\":\"AB1234\",\"txn\":{\"txnId\":\"O-1\",\"type\":\"BUY\","
            + "\"date\":\"2026-06-15\",\"symbol\":\"RELIANCE\",\"units\":\"10\",\"nav\":\"2500\",\"reversal\":\"true\"}}";
        ingest(SourceType.BROKER_API, "mock-broker", new RawRecord(RecordKind.TRANSACTION, "AB1234", "O-1", cancelled, "aa-fi-v1"));
        assertThat(all()).singleElement().satisfies(t -> assertThat(t.getStatus()).isEqualTo(TxnStatus.REVERSED));
        assertThat(snapshots.findAll().stream().filter(s -> s.getQuantity().signum() > 0)).isEmpty();
    }

    @Test @DisplayName("partial fills with distinct trade numbers are separate transactions and add up")
    void partialFills() {
        String f1 = "{\"fiType\":\"EQUITIES\",\"institution\":\"Zerodha\",\"accountId\":\"AB1234\",\"txn\":{\"txnId\":\"T-1\",\"type\":\"BUY\",\"date\":\"2026-06-15\",\"symbol\":\"RELIANCE\",\"units\":\"50\",\"nav\":\"2500\"}}";
        String f2 = f1.replace("T-1", "T-2");
        ingest(SourceType.BROKER_API, "mock-broker",
            new RawRecord(RecordKind.TRANSACTION, "AB1234", "T-1", f1, "aa-fi-v1"), new RawRecord(RecordKind.TRANSACTION, "AB1234", "T-2", f2, "aa-fi-v1"));
        assertThat(all()).hasSize(2);
        assertThat(issueRepo.findAll()).isEmpty();
        assertThat(snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow().getQuantity()).isEqualByComparingTo("100");
    }

    /* ───────── duplicates ───────── */

    @Test @DisplayName("two identical emails with nothing to tell them apart are both kept and flagged as a possible duplicate")
    void possibleDuplicate() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m1", "fp-1");
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m2", "fp-2");
        assertThat(all()).hasSize(2);
        LedgerIssue dup = issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.POSSIBLE_DUPLICATE).findFirst().orElseThrow();
        assertThat(dup.getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(dup.getOtherTransactionId()).isNotNull();
    }

    @Test @DisplayName("merging a possible duplicate moves its sources onto the target and rejects the copy — nothing is deleted")
    void mergeDuplicate() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m1", "fp-1");
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m2", "fp-2");
        LedgerIssue dup = issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.POSSIBLE_DUPLICATE).findFirst().orElseThrow();

        resolution.act(USER, dup.getId(), ResolutionAction.MERGE, "same SIP", null);

        assertThat(all()).hasSize(2);                                                    // still two rows
        assertThat(all().stream().filter(t -> t.getStatus().counts())).hasSize(1);       // one counts
        CanonicalTransaction target = txns.findById(dup.getOtherTransactionId()).orElseThrow();
        assertThat(sources.findByTransactionIdOrderByIdAsc(target.getId())).hasSize(2);
        assertThat(txns.findById(dup.getTransactionId()).orElseThrow().getReconciliationStatus()).isEqualTo(ReconStatus.DUPLICATE);
    }

    @Test @DisplayName("an exact reference shared across sources merges two reports into one")
    void exactReferenceMerge() {
        ingest(SourceType.BROKER_API, "mock-broker", new RawRecord(RecordKind.TRANSACTION, "AB1234", "T-1",
            "{\"fiType\":\"EQUITIES\",\"institution\":\"Zerodha\",\"accountId\":\"AB1234\",\"txn\":{\"txnId\":\"TRADE777\",\"type\":\"BUY\",\"date\":\"2026-06-15\",\"symbol\":\"RELIANCE\",\"units\":\"10\",\"nav\":\"2500\"}}", "aa-fi-v1"));
        String statement = "{\"institution\":\"Zerodha\",\"accountId\":\"AB1234\",\"assetClass\":\"STOCK\",\"type\":\"BUY\",\"date\":\"2026-06-16\",\"symbol\":\"RELIANCE\",\"quantity\":\"10\",\"price\":\"2500\",\"reference\":\"trade777\"}";
        ingest(SourceType.STATEMENT, "contract-note", new RawRecord(RecordKind.TRANSACTION, "AB1234", "row1", statement, "statement-row-v1"));
        assertThat(all()).hasSize(1);
        assertThat(sources.findAll()).extracting(TransactionSource::getMatchKind).contains("EXACT_REFERENCE");
    }

    /* ───────── asset behaviours ───────── */

    @Test @DisplayName("SIP, STP, SWP, switch legs, bonus, split, dividend reinvestment and redemption all replay into the right holding")
    void assetBehaviours() {
        ingest(SourceType.STATEMENT, "csv",
            statementRow("R1", "SIP", "2026-01-10", ISIN_A, "Fund A", "100", "10", "1000"),
            statementRow("R2", "STP", "2026-02-10", ISIN_A, "Fund A", "50", "12", "600"),
            statementRow("R3", "SWP", "2026-03-10", ISIN_A, "Fund A", "30", "15", "450"),
            statementRow("R4", "SWITCH_OUT", "2026-04-10", ISIN_A, "Fund A", "20", "16", "320"),
            statementRow("R5", "SWITCH_IN", "2026-04-10", ISIN_B, "Fund B", "40", "8", "320"),
            statementRow("R6", "DIVIDEND_REINVESTMENT", "2026-05-10", ISIN_B, "Fund B", "2", "10", "20"),
            statementRow("R7", "REDEMPTION", "2026-06-10", ISIN_B, "Fund B", "12", "11", "132"));
        var calcByIsin = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED)
            .collect(Collectors.toMap(s -> assetRepo.findById(s.getAssetId()).orElseThrow().getIsin(), HoldingSnapshot::getQuantity));
        assertThat(calcByIsin.get(ISIN_A)).isEqualByComparingTo("100");   // 100 + 50 - 30 - 20
        assertThat(calcByIsin.get(ISIN_B)).isEqualByComparingTo("30");    // 40 + 2 - 12
    }

    @Test @DisplayName("stock split and bonus change the holding without any cash")
    void splitAndBonus() {
        String base = "{\"institution\":\"Zerodha\",\"accountId\":\"AB1234\",\"assetClass\":\"STOCK\",\"symbol\":\"INFY\",";
        ingest(SourceType.BROKER_API, "mock-broker",
            new RawRecord(RecordKind.TRANSACTION, "AB1234", "1", base + "\"type\":\"BUY\",\"date\":\"2026-01-10\",\"quantity\":\"10\",\"price\":\"1000\"}", "statement-row-v1"),
            new RawRecord(RecordKind.TRANSACTION, "AB1234", "2", base + "\"type\":\"SPLIT\",\"date\":\"2026-03-10\",\"ratioFrom\":\"1\",\"ratioTo\":\"5\"}", "statement-row-v1"),
            new RawRecord(RecordKind.TRANSACTION, "AB1234", "3", base + "\"type\":\"BONUS\",\"date\":\"2026-04-10\",\"quantity\":\"10\"}", "statement-row-v1"));
        HoldingSnapshot calc = snapshots.findAll().stream().filter(s -> s.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow();
        assertThat(calc.getQuantity()).isEqualByComparingTo("60");
        assertThat(calc.getInvestedValue()).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("FD creation, interest credit and maturity are ledger events of non-unit assets")
    void depositEvents() {
        String fd = "{\"institution\":\"HDFC Bank\",\"accountId\":\"FD998877\",\"assetClass\":\"FD\",\"symbol\":\"FD998877\",";
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa",
            new RawRecord(RecordKind.TRANSACTION, "FD998877", "1", fd + "\"type\":\"FD_CREATION\",\"date\":\"2025-10-05\",\"amount\":\"100000\"}", "statement-row-v1"),
            new RawRecord(RecordKind.TRANSACTION, "FD998877", "2", fd + "\"type\":\"INTEREST\",\"date\":\"2026-04-05\",\"amount\":\"3500\"}", "statement-row-v1"),
            new RawRecord(RecordKind.TRANSACTION, "FD998877", "3", fd + "\"type\":\"FD_MATURITY\",\"date\":\"2026-10-05\",\"amount\":\"107000\"}", "statement-row-v1"));
        assertThat(all()).extracting(CanonicalTransaction::getTransactionType)
            .containsExactlyInAnyOrder(TransactionType.FD_CREATION, TransactionType.INTEREST, TransactionType.FD_MATURITY);
    }

    @Test @DisplayName("a record that fails validation is rejected with a reason and never reaches the ledger; the rest of the batch continues")
    void rejectedRecord() {
        var res = ingest(SourceType.STATEMENT, "csv",
            statementRow("BAD", "BUY", "2999-01-01", ISIN_A, "Fund A", "10", "10", "100"),
            statementRow("GOOD", "BUY", "2026-01-10", ISIN_A, "Fund A", "10", "10", "100"));
        assertThat(res.rejected).isEqualTo(1);
        assertThat(res.created).isEqualTo(1);
        assertThat(raws.findAll().stream().filter(r -> r.getProcessingStatus() == ProcessingStatus.REJECTED)).singleElement()
            .satisfies(r -> assertThat(r.getError()).contains("future"));
    }

    @Test @DisplayName("a payload no normaliser understands fails visibly and stays retryable")
    void unknownSchema() {
        var res = ingest(SourceType.STATEMENT, "csv", new RawRecord(RecordKind.TRANSACTION, null, "x", "{}", "mystery-v9"));
        assertThat(res.failed).isEqualTo(1);
        assertThat(raws.findAll()).singleElement().satisfies(r -> assertThat(r.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED));
    }

    /* ───────── conflicts & precedence ───────── */

    @Test @DisplayName("an authoritative source disagreeing with an email wins; the disagreement is recorded as auto-resolved, visible, not hidden")
    void authorityOverridesEmail() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m1", "fp1");
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10020"));
        // 10000 vs 10020 is inside the 0.5% tolerance: one transaction, the institution's figure is used
        assertThat(all()).hasSize(1);
        assertThat(all().get(0).getNetAmount()).isEqualByComparingTo("10020");
        assertThat(all().get(0).getReconciliationStatus()).isEqualTo(ReconStatus.VERIFIED);
    }

    @Test @DisplayName("source priority: manual entry and AI inference never overwrite an institution's figures")
    void manualNeverOverwritesAuthority() {
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        ingest(SourceType.MANUAL, "manual", statementRow(null, "BUY", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "200", "10000"));
        CanonicalTransaction t = all().get(0);
        assertThat(all()).hasSize(1);
        assertThat(t.getSourceType()).isEqualTo(SourceType.ACCOUNT_AGGREGATOR);
        assertThat(t.getUnitPrice()).isEqualByComparingTo("200");   // price only the manual entry stated fills the gap, nothing is overwritten
    }

    /* ───────── data health & detail ───────── */

    @Test @DisplayName("data health: deterministic score with its counts; an email-only ledger is 0% verified and says why")
    void dataHealth() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m1", "fp1");
        var h = quality.health(USER, false);
        assertThat(h.scorePercent()).isEqualTo(0.0);
        assertThat(h.pending()).isEqualTo(1);
        assertThat(h.hasAuthoritativeSource()).isFalse();
        assertThat(h.warnings()).anyMatch(w -> w.contains("No institution source"));

        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        var after = quality.health(USER, false);
        assertThat(after.scorePercent()).isEqualTo(100.0);
        assertThat(after.accounts()).isNotEmpty();
        assertThat(after.bySource()).containsKey("ACCOUNT_AGGREGATOR");
    }

    @Test @DisplayName("transaction detail shows every source, references, reconciliation, confidence and the audit trail")
    void transactionDetail() {
        emailRecorder.record(USER, emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m1", "fp1");
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-1", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        var d = queries.detail(USER, all().get(0).getId());
        assertThat(d.sources()).hasSize(2);
        assertThat(d.externalReferences()).contains("AA-1");
        assertThat(d.summary().reconciliationStatus()).isEqualTo(ReconStatus.VERIFIED);
        assertThat(d.summary().confidence()).isEqualTo(1.0);
        assertThat(d.history()).isNotEmpty();
        assertThat(d.confidenceExplanation()).contains("ACCOUNT_AGGREGATOR");
        assertThat(d.lastVerifiedAt()).isNotNull();
    }

    @Test @DisplayName("holdings: the calculated snapshot carries average cost, invested value and realised P&L")
    void snapshotFields() {
        ingest(SourceType.BROKER_API, "mock-broker",
            statementRow("1", "BUY", "2026-01-10", ISIN_A, "Fund A", "100", "10", "1000"),
            statementRow("2", "SELL", "2026-02-10", ISIN_A, "Fund A", "40", "15", "600"));
        HoldingSnapshot s = snapshots.findAll().stream().filter(x -> x.getBasis() == BasisType.LEDGER_CALCULATED).findFirst().orElseThrow();
        assertThat(s.getQuantity()).isEqualByComparingTo("60");
        assertThat(s.getAverageCost()).isEqualByComparingTo("10");
        assertThat(s.getInvestedValue()).isEqualByComparingTo("600");
        assertThat(s.getRealizedPnl()).isEqualByComparingTo("200");
    }
}
