package com.marketai.dataplatform.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.common.ledger.Provenance;
import com.marketai.dataplatform.domain.*;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The migration path: history already in the legacy tables enters the canonical ledger without being altered or duplicated. */
class LegacyBackfillIntegrationTest extends DataPlatformTestSupport {

    @Autowired LegacyBackfillService backfill;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolios;
    @Autowired HoldingRepository holdingRepo;
    @Autowired TransactionRepository legacy;

    User user;
    Holding holding;

    @BeforeEach
    void legacyFixtures() {
        legacy.deleteAllInBatch(); holdingRepo.deleteAllInBatch(); portfolios.deleteAllInBatch(); users.deleteAllInBatch();
        user = users.save(User.builder().name("u").email("u@x.in").password("x").build());
        Portfolio p = portfolios.save(Portfolio.builder().user(user).name("Main").build());
        holding = holdingRepo.save(Holding.builder().portfolio(p).symbol("HDFCFLEXI.MF").name("HDFC Flexi Cap").isin(ISIN_A)
            .quantity(new BigDecimal("150")).averageCost(new BigDecimal("100")).folio("FOLIO1234").build());
    }

    private Transaction legacyTxn(String date, String qty, String price, Provenance prov) {
        return legacy.save(Transaction.builder().holding(holding).type(Transaction.TransactionType.BUY)
            .quantity(new BigDecimal(qty)).price(new BigDecimal(price)).transactionDate(LocalDate.parse(date)).provenance(prov).build());
    }

    private static Provenance fromEmail(String fingerprint) {
        return Provenance.builder().sourceEmailId("gmail-msg-1").sourceFingerprint(fingerprint).extractionMethod("EMAIL_LLM").extractionConfidence(0.9).build();
    }

    @Test @DisplayName("history is backfilled as EMAIL when it came from an email and MANUAL otherwise; the legacy rows are left untouched")
    void classifiesAndPreserves() {
        Transaction e = legacyTxn("2026-06-15", "100", "100", fromEmail("fp-A"));
        Transaction m = legacyTxn("2026-07-15", "50", "100", null);

        var summary = backfill.backfill(user.getId());

        assertThat(summary.examined()).isEqualTo(2);
        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.email()).isEqualTo(1);
        assertThat(summary.manual()).isEqualTo(1);
        var ledger = txns.findByUserIdAndStatusIn(user.getId(), java.util.List.of(TxnStatus.values()));
        assertThat(ledger).hasSize(2);
        assertThat(ledger.stream().filter(t -> t.getLegacyTransactionId().equals(e.getId())).findFirst().orElseThrow().getSourceType()).isEqualTo(SourceType.EMAIL);
        assertThat(ledger.stream().filter(t -> t.getLegacyTransactionId().equals(m.getId())).findFirst().orElseThrow().getSourceType()).isEqualTo(SourceType.MANUAL);
        // nothing here is institution-verified, and the backfill never claims it is
        assertThat(ledger).allSatisfy(t -> assertThat(t.getReconciliationStatus()).isNotEqualTo(ReconStatus.VERIFIED));
        assertThat(legacy.count()).isEqualTo(2);
        assertThat(holdingRepo.findById(holding.getId()).orElseThrow().getQuantity()).isEqualByComparingTo("150");
    }

    @Test @DisplayName("running the backfill again is a no-op")
    void idempotent() {
        legacyTxn("2026-06-15", "100", "100", fromEmail("fp-A"));
        legacyTxn("2026-07-15", "50", "100", null);
        backfill.backfill(user.getId());
        var again = backfill.backfill(user.getId());
        assertThat(again.created()).isZero();
        assertThat(again.alreadyPresent()).isEqualTo(2);
        assertThat(txns.count()).isEqualTo(2);
    }

    @Test @DisplayName("two legacy rows that look identical (same fund, day, units, price) are both kept: they were booked as separate rows")
    void identicalLegacyRowsStaySeparate() {
        legacyTxn("2026-06-15", "100", "100", null);
        legacyTxn("2026-06-15", "100", "100", null);
        backfill.backfill(user.getId());
        assertThat(txns.count()).isEqualTo(2);
    }

    @Test @DisplayName("an email the importer later records live, whose legacy row was already backfilled, is recognised and not duplicated")
    void liveEmailAfterBackfill() {
        legacyTxn("2026-08-15", "52.431", "190.73", fromEmail("fp-AUG"));
        backfill.backfill(user.getId());
        assertThat(txns.count()).isEqualTo(1);

        emailRecorder.record(user.getId(), emailSip("2026-08-15", ISIN_A, "HDFC Flexi Cap", "10000"), "gmail-msg-1", "fp-AUG");

        assertThat(txns.count()).isEqualTo(1);
    }

    @Test @DisplayName("backfilled history is later verified, not replaced, when an institution reports the same transactions")
    void backfilledHistoryGetsVerified() {
        legacyTxn("2026-06-15", "50", "200", fromEmail("fp-A"));
        backfill.backfill(user.getId());
        pipeline.ingest(new IngestionPipeline.Request(user.getId(), null, null, SourceType.ACCOUNT_AGGREGATOR, "mock-aa", ProviderMode.MOCK,
            java.util.List.of(aaTxn("AA-1", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "50", "10000")), null));
        var ledger = txns.findByUserIdAndStatusIn(user.getId(), java.util.List.of(TxnStatus.values()));
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getReconciliationStatus()).isEqualTo(ReconStatus.VERIFIED);
        assertThat(ledger.get(0).getLegacyTransactionId()).isNotNull();
    }

    @Test @DisplayName("after an institution refines the placeholder account there is one holding row, not a ghost on the old account")
    void noGhostHoldingAfterRefinement() {
        legacyTxn("2026-06-15", "50", "200", null);
        legacyTxn("2026-07-15", "50", "200", null);
        backfill.backfill(user.getId());
        pipeline.ingest(new IngestionPipeline.Request(user.getId(), null, null, SourceType.ACCOUNT_AGGREGATOR, "mock-aa", ProviderMode.MOCK,
            java.util.List.of(aaTxn("AA-6", "BUY", "2026-06-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
                aaTxn("AA-7", "BUY", "2026-07-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"),
                aaHolding(ISIN_A, "HDFC Flexi Cap", "100", "2026-10-05")), null));
        var calc = snapshots.findAll().stream().filter(x -> x.getBasis() == BasisType.LEDGER_CALCULATED).toList();
        assertThat(calc).hasSize(1);
        assertThat(calc.get(0).getQuantity()).isEqualByComparingTo("100");
        assertThat(issueRepo.findAll()).noneMatch(i -> i.getType() == IssueType.HOLDING_MISMATCH);
    }

    @Test @DisplayName("a user with no legacy history backfills to nothing")
    void emptyUser() {
        var s = backfill.backfill(user.getId());
        assertThat(s.examined()).isZero();
        assertThat(s.created()).isZero();
    }
}
