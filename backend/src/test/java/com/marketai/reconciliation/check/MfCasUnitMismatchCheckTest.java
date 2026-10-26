package com.marketai.reconciliation.check;

import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CAS statement's own stated closing unit balance for a folio, checked against what this
 * app's ledger computes for the matching holding — report only, never repaired (see the
 * class-level javadoc on {@link MfCasUnitMismatchCheck} for why).
 */
class MfCasUnitMismatchCheckTest {

    private static final Long USER = 1L;

    private CasBalanceSnapshotRepository snapshotRepo;
    private HoldingRepository holdingRepo;
    private MfCasUnitMismatchCheck check;

    private void setUp() {
        snapshotRepo = mock(CasBalanceSnapshotRepository.class);
        holdingRepo = mock(HoldingRepository.class);
        check = new MfCasUnitMismatchCheck(snapshotRepo, holdingRepo,
            mock(com.marketai.portfolio.repository.TransactionRepository.class),
            mock(com.marketai.dataplatform.repo.HoldingSnapshotRepository.class),
            mock(com.marketai.dataplatform.repo.FinancialAccountRepository.class),
            mock(com.marketai.dataplatform.repo.FinancialAssetRepository.class));
    }

    private static CasBalanceSnapshot snapshot(String folio, String schemeCode, LocalDate asOfDate, String units) {
        return CasBalanceSnapshot.builder()
            .userId(USER).folio(folio).schemeCode(schemeCode).asOfDate(asOfDate)
            .statedUnits(new BigDecimal(units)).build();
    }

    private static Holding holding(Long id, String folio, String amfiSchemeCode, String quantity) {
        return Holding.builder()
            .id(id).symbol("TESTFUND.MF").name("Test Fund").folio(folio)
            .amfiSchemeCode(amfiSchemeCode).quantity(new BigDecimal(quantity))
            .averageCost(BigDecimal.TEN).build();
    }

    @Test
    @DisplayName("units disagreeing beyond tolerance emits an issue, describing both figures")
    void mismatchBeyondToleranceEmitsIssue() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        ReconciliationIssue issue = issues.get(0);
        assertThat(issue.getDomain()).isEqualTo("PORTFOLIO");
        assertThat(issue.getType()).isEqualTo("MF_CAS_UNIT_MISMATCH");
        assertThat(issue.getReferenceId()).isEqualTo(10L);
        assertThat(issue.getDescription()).contains("1200").contains("1000").contains("200");
    }

    @Test
    @DisplayName("units agreeing within tolerance emits no issue")
    void withinToleranceEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1000.0050")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("no snapshot at all for a folio means no issue — nothing to compare")
    void noSnapshotEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER)).thenReturn(List.of());

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a snapshot with no matching holding yet (by folio or scheme) is not flagged")
    void noMatchingHoldingEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1")).thenReturn(List.of());
        when(holdingRepo.findByUserIdAndAmfiSchemeCode(USER, "HDFC001")).thenReturn(List.of());

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("only the latest snapshot per folio is compared — an older one is superseded")
    void onlyLatestSnapshotPerFolioIsCompared() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER)).thenReturn(List.of(
            snapshot("F1", "HDFC001", LocalDate.of(2026, 2, 28), "1000.0000"), // latest — matches ledger
            snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")  // stale — would mismatch
        ));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("two schemes in one folio are each compared with their own holding, not added together")
    void schemesInOneFolioAreKeptApart() {
        setUp();
        CasBalanceSnapshot flexi = snapshot("F1", null, LocalDate.of(2026, 1, 31), "100.0000");
        flexi.setIsin("INF179K01UT0");
        CasBalanceSnapshot liquid = snapshot("F1", null, LocalDate.of(2026, 1, 31), "40.0000");
        liquid.setIsin("INF179K01VK7");
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER)).thenReturn(List.of(flexi, liquid));
        Holding a = holding(10L, "F1", "1001", "100.0000");
        a.setIsin("INF179K01UT0");
        Holding b = holding(11L, "F1", "1002", "25.0000");
        b.setIsin("INF179K01VK7");
        when(holdingRepo.findByUserIdAndFolio(USER, "F1")).thenReturn(List.of(a, b));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.getReferenceId()).isEqualTo(11L);
            assertThat(i.getDescription()).contains("40").contains("25").contains("15");
        });
    }

    @Test
    @DisplayName("a line that can't be told apart from a folio's other schemes is not compared")
    void ambiguousLineIsSkipped() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", null, LocalDate.of(2026, 1, 31), "999.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "1001", "100"), holding(11L, "F1", "1002", "25")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("falls back to matching by AMFI scheme code when no holding carries the folio")
    void fallsBackToSchemeCodeMatch() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1")).thenReturn(List.of());
        when(holdingRepo.findByUserIdAndAmfiSchemeCode(USER, "HDFC001"))
            .thenReturn(List.of(holding(11L, null, "HDFC001", "1000.0000")));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getReferenceId()).isEqualTo(11L);
    }
}
