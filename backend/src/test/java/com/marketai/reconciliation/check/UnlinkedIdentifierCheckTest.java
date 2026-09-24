package com.marketai.reconciliation.check;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An MF holding with no folio recorded cannot be told apart from a same-named scheme at a
 * different AMC or account. This check only surfaces that gap; it does not change how
 * ParsedEmailImporter.importMf matches or creates holdings.
 */
class UnlinkedIdentifierCheckTest {

    private static final Long USER = 1L;

    private static Portfolio portfolio(Long id) {
        return Portfolio.builder().id(id).name("My Portfolio").build();
    }

    private static Holding mfHolding(Long id, String folio) {
        return Holding.builder()
            .id(id).symbol("TESTFUND.MF").name("Test Fund").folio(folio)
            .quantity(BigDecimal.TEN).averageCost(BigDecimal.TEN).build();
    }

    private static Holding equityHolding(Long id) {
        return Holding.builder()
            .id(id).symbol("RELIANCE.NS").name("Reliance Industries")
            .quantity(BigDecimal.TEN).averageCost(BigDecimal.TEN).build();
    }

    private List<ReconciliationIssue> runWith(Portfolio p, List<Holding> holdings) {
        PortfolioRepository portfolioRepo = mock(PortfolioRepository.class);
        HoldingRepository holdingRepo = mock(HoldingRepository.class);
        when(portfolioRepo.findByUserId(USER)).thenReturn(List.of(p));
        when(holdingRepo.findByPortfolioId(p.getId())).thenReturn(holdings);
        return new UnlinkedIdentifierCheck(portfolioRepo, holdingRepo).run(USER);
    }

    @Test
    @DisplayName("an MF holding with no folio is flagged")
    void mfHoldingWithNoFolioIsFlagged() {
        Portfolio p = portfolio(100L);
        var issues = runWith(p, List.of(mfHolding(1L, null)));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getDomain()).isEqualTo("PORTFOLIO");
        assertThat(issues.get(0).getType()).isEqualTo("UNLINKED_IDENTIFIER");
        assertThat(issues.get(0).getReferenceId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an MF holding with a folio is not flagged")
    void mfHoldingWithFolioIsNotFlagged() {
        Portfolio p = portfolio(100L);
        var issues = runWith(p, List.of(mfHolding(1L, "12345")));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("a non-MF (equity) holding is never flagged, folio or not")
    void equityHoldingIsNeverFlagged() {
        Portfolio p = portfolio(100L);
        var issues = runWith(p, List.of(equityHolding(2L)));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("a blank folio is treated the same as a missing one")
    void blankFolioIsFlagged() {
        Portfolio p = portfolio(100L);
        var issues = runWith(p, List.of(mfHolding(3L, "  ")));

        assertThat(issues).hasSize(1);
    }

    @Test
    @DisplayName("no holdings at all means no findings")
    void noHoldingsMeansNoFindings() {
        Portfolio p = portfolio(100L);
        assertThat(runWith(p, List.of())).isEmpty();
    }
}
