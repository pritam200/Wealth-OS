package com.marketai.redemption.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * The estimated tax recorded against a redemption.
 *
 * <p>Two rules that were previously wrong here, both of which understate what the user owes —
 * the direction that hurts, because someone acting on the figure sets aside too little and finds
 * out at filing. The short-term rate is 20% (Finance (No.2) Act 2024, not the old 15%), and the
 * ₹1,25,000 long-term exemption is a per-financial-year aggregate, not a fresh allowance on every
 * redemption.
 */
class RedemptionTaxEstimateTest {

    private static final Long USER = 11L;

    private MfRedemptionRepository redemptionRepo;
    private RedemptionService service;

    @BeforeEach
    void setUp() {
        redemptionRepo = mock(MfRedemptionRepository.class);
        service = new RedemptionService(
            redemptionRepo,
            mock(PortfolioRepository.class),
            mock(HoldingRepository.class),
            mock(TechnicalIndicatorService.class));

        when(redemptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any())).thenReturn(null);
    }

    /** A holding bought {@code daysAgo} ago, at ₹100 average cost. */
    private Holding holding(long daysAgo) {
        return Holding.builder().id(1L).symbol("PPFAS.MF").name("Parag Parikh Flexi Cap")
            .quantity(new BigDecimal("1000")).averageCost(new BigDecimal("100"))
            .buyDate(LocalDate.now().minusDays(daysAgo))
            .build();
    }

    private MfRedemption redeem(long daysHeld, String units, String nav) {
        service.recordRedemption(USER, holding(daysHeld), new BigDecimal(units), new BigDecimal(nav));
        ArgumentCaptor<MfRedemption> captor = ArgumentCaptor.forClass(MfRedemption.class);
        verify(redemptionRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("short-term gains are taxed at 20%, not the pre-2024 15%")
    void shortTermUsesTheCurrentRate() {
        // 1,000 units @ ₹200 against a ₹100 basis = ₹1,00,000 gain, held 200 days.
        MfRedemption r = redeem(200, "1000", "200");

        assertThat(r.getGainType()).isEqualTo("STCG");
        assertThat(r.getCapitalGain()).isEqualByComparingTo("100000.00");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("20000.00");
    }

    @Test
    void theOneYearBoundaryIsAtThreeHundredAndSixtyFiveDays() {
        assertThat(redeem(364, "1000", "200").getGainType()).isEqualTo("STCG");
    }

    @Test
    void longTermGainWithinTheExemptionIsUntaxed() {
        // ₹1,00,000 gain, held over a year, nothing else realised this FY.
        MfRedemption r = redeem(400, "1000", "200");

        assertThat(r.getGainType()).isEqualTo("LTCG");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the long-term exemption is consumed across the financial year, not re-granted")
    void exemptionIsConsumedAcrossTheYear() {
        // ₹3,60,000 of long-term gain already realised this FY, so the ₹1.25L is fully used.
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any()))
            .thenReturn(new BigDecimal("360000"));

        MfRedemption r = redeem(400, "1000", "220");

        // ₹1,20,000 gain, zero exemption left → 12.5% of the whole gain.
        assertThat(r.getCapitalGain()).isEqualByComparingTo("120000.00");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("15000.00");
    }

    @Test
    void aPartiallyUsedExemptionOnlyShieldsWhatIsLeft() {
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any()))
            .thenReturn(new BigDecimal("100000"));   // ₹25,000 of exemption left

        MfRedemption r = redeem(400, "1000", "200"); // ₹1,00,000 gain

        // (1,00,000 − 25,000) × 12.5%
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("9375.00");
    }

    @Test
    void aLossIsNeverTaxed() {
        MfRedemption r = redeem(400, "1000", "80");

        assertThat(r.getCapitalGain()).isEqualByComparingTo("-20000.00");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("0.00");
    }
}
