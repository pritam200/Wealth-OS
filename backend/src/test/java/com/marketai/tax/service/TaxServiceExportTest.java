package com.marketai.tax.service;

import com.marketai.income.repository.IncomeRepository;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.tax.dto.CapitalGainsExportRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ITR-filing export row builder in {@link TaxService#capitalGainsExport}.
 *
 * <p>Mirrors the fixture shape used in {@code RedemptionTaxEstimateTest} — only MfRedemption
 * carries a persisted acquisition/disposal split, so that's the export's source of truth.
 */
class TaxServiceExportTest {

    private static final Long USER = 11L;
    private static final int FY_START = 2026;

    private MfRedemptionRepository redemptionRepo;
    private TaxService service;

    @BeforeEach
    void setUp() {
        redemptionRepo = mock(MfRedemptionRepository.class);
        service = new TaxService(mock(IncomeRepository.class), redemptionRepo);
    }

    private MfRedemption redemption(String symbol, LocalDate saleDate, long holdingDays,
                                    String units, String invested, String proceeds,
                                    String gainType, String gain) {
        return MfRedemption.builder()
            .userId(USER).symbol(symbol).fundName(symbol + " Fund")
            .unitsRedeemed(new BigDecimal(units))
            .investedValueAtRedemption(new BigDecimal(invested))
            .redeemedAmount(new BigDecimal(proceeds))
            .redemptionDate(saleDate).holdingPeriodDays(holdingDays)
            .gainType(gainType).capitalGain(new BigDecimal(gain))
            .build();
    }

    @Test
    @DisplayName("rows outside the requested financial year are excluded")
    void filtersToTheRequestedFinancialYear() {
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of(
            redemption("PPFAS.MF", LocalDate.of(2026, 6, 1), 400, "1000", "100000", "220000", "LTCG", "120000"),
            redemption("HDFC.MF", LocalDate.of(2025, 6, 1), 400, "500", "50000", "70000", "LTCG", "20000")));

        List<CapitalGainsExportRow> rows = service.capitalGainsExport(USER, FY_START);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).assetSymbol()).isEqualTo("PPFAS.MF");
    }

    @Test
    @DisplayName("row values are carried through unchanged, and acquisition date is derived from the holding period")
    void mapsFieldsFromTheRedemption() {
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of(
            redemption("PPFAS.MF", LocalDate.of(2026, 6, 1), 400, "1000", "100000", "220000", "LTCG", "120000")));

        CapitalGainsExportRow row = service.capitalGainsExport(USER, FY_START).get(0);

        assertThat(row.assetSymbol()).isEqualTo("PPFAS.MF");
        assertThat(row.assetName()).isEqualTo("PPFAS.MF Fund");
        assertThat(row.isin()).isNull();
        assertThat(row.acquisitionDate()).isEqualTo(LocalDate.of(2026, 6, 1).minusDays(400));
        assertThat(row.saleDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(row.quantity()).isEqualByComparingTo("1000");
        assertThat(row.acquisitionValue()).isEqualByComparingTo("100000");
        assertThat(row.saleValue()).isEqualByComparingTo("220000");
        assertThat(row.gainType()).isEqualTo("LTCG");
        assertThat(row.gainOrLoss()).isEqualByComparingTo("120000");
    }

    @Test
    @DisplayName("the exemption is consumed cumulatively across rows, not re-granted per sale")
    void exemptionIsAppliedAsARunningBalanceAcrossRows() {
        // First LTCG sale uses 90,000 of the 1,25,000 exemption; the second only has 35,000 left.
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of(
            redemption("B.MF", LocalDate.of(2026, 8, 1), 400, "100", "10000", "100000", "LTCG", "90000"),
            redemption("A.MF", LocalDate.of(2026, 5, 1), 400, "100", "10000", "100000", "LTCG", "90000")));

        List<CapitalGainsExportRow> rows = service.capitalGainsExport(USER, FY_START);

        assertThat(rows).hasSize(2);
        // Rows come back in chronological (redemption date) order.
        assertThat(rows.get(0).assetSymbol()).isEqualTo("A.MF");
        assertThat(rows.get(0).exemptionApplied()).isEqualByComparingTo("90000");
        assertThat(rows.get(1).assetSymbol()).isEqualTo("B.MF");
        assertThat(rows.get(1).exemptionApplied()).isEqualByComparingTo("35000");
    }

    @Test
    @DisplayName("short-term gains never consume the exemption")
    void stcgRowsGetNoExemption() {
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of(
            redemption("A.MF", LocalDate.of(2026, 5, 1), 100, "100", "10000", "30000", "STCG", "20000")));

        CapitalGainsExportRow row = service.capitalGainsExport(USER, FY_START).get(0);

        assertThat(row.exemptionApplied()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a loss consumes no exemption")
    void aLossConsumesNoExemption() {
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of(
            redemption("A.MF", LocalDate.of(2026, 5, 1), 400, "100", "20000", "10000", "LTCG", "-10000")));

        CapitalGainsExportRow row = service.capitalGainsExport(USER, FY_START).get(0);

        assertThat(row.exemptionApplied()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("with no fyStartYear given, the current financial year is used")
    void defaultsToTheCurrentFinancialYear() {
        when(redemptionRepo.findByUserIdOrderByRedemptionDateDesc(anyLong())).thenReturn(List.of());

        assertThat(service.capitalGainsExport(USER, null)).isEmpty();
    }
}
