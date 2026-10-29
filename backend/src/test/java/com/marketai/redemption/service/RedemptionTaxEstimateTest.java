package com.marketai.redemption.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * The estimated tax recorded against a redemption.
 *
 * <p>FIFO lot matching drives ST/LT when the ledger has purchases. Tax always includes the 4%
 * cess so it agrees with {@code TaxService} and {@code DisposalCalculator}. The ₹1,25,000
 * long-term exemption is a per-financial-year aggregate.
 */
class RedemptionTaxEstimateTest {

    private static final Long USER = 11L;

    private MfRedemptionRepository redemptionRepo;
    private TransactionRepository transactionRepo;
    private RedemptionService service;

    @BeforeEach
    void setUp() {
        redemptionRepo = mock(MfRedemptionRepository.class);
        transactionRepo = mock(TransactionRepository.class);
        service = new RedemptionService(
            redemptionRepo,
            mock(PortfolioRepository.class),
            mock(HoldingRepository.class),
            transactionRepo,
            mock(TechnicalIndicatorService.class));

        when(redemptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any())).thenReturn(null);
        when(transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(anyLong())).thenReturn(List.of());
    }

    /** A holding bought {@code daysAgo} ago, at ₹100 average cost. */
    private Holding holding(long daysAgo) {
        return Holding.builder().id(1L).symbol("PPFAS.MF").name("Parag Parikh Flexi Cap")
            .quantity(new BigDecimal("1000")).averageCost(new BigDecimal("100"))
            .buyDate(LocalDate.now().minusDays(daysAgo))
            .build();
    }

    private MfRedemption redeem(long daysHeld, String units, String nav) {
        Holding h = holding(daysHeld);
        service.recordRedemption(USER, h, new BigDecimal(units), new BigDecimal(nav),
            h.getAverageCost().multiply(new BigDecimal(units)), LocalDate.now());
        ArgumentCaptor<MfRedemption> captor = ArgumentCaptor.forClass(MfRedemption.class);
        verify(redemptionRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("short-term gains are taxed at 20% plus 4% cess")
    void shortTermUsesTheCurrentRate() {
        // 1,000 units @ ₹200 against a ₹100 basis = ₹1,00,000 gain, held 200 days.
        MfRedemption r = redeem(200, "1000", "200");

        assertThat(r.getGainType()).isEqualTo("STCG");
        assertThat(r.getCapitalGain()).isEqualByComparingTo("100000.00");
        // 20% of 1,00,000 = 20,000; + 4% cess = 20,800
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("20800.00");
    }

    @Test
    void theOneYearBoundaryUsesMoreThanTwelveMonths() {
        // CapitalGainsRates: sold on the first anniversary is still short-term.
        LocalDate buy = LocalDate.now().minusMonths(12);
        Holding h = Holding.builder().id(1L).symbol("PPFAS.MF").name("F")
            .quantity(new BigDecimal("1000")).averageCost(new BigDecimal("100"))
            .buyDate(buy).build();
        service.recordRedemption(USER, h, new BigDecimal("1000"), new BigDecimal("200"),
            new BigDecimal("100000"), LocalDate.now());
        ArgumentCaptor<MfRedemption> captor = ArgumentCaptor.forClass(MfRedemption.class);
        verify(redemptionRepo).save(captor.capture());
        assertThat(captor.getValue().getGainType()).isEqualTo("STCG");
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
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any()))
            .thenReturn(new BigDecimal("360000"));

        MfRedemption r = redeem(400, "1000", "220");

        // ₹1,20,000 gain, zero exemption left → 12.5% = 15,000; + 4% cess = 15,600
        assertThat(r.getCapitalGain()).isEqualByComparingTo("120000.00");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("15600.00");
    }

    @Test
    void aPartiallyUsedExemptionOnlyShieldsWhatIsLeft() {
        when(redemptionRepo.sumLongTermGainsInFy(anyLong(), any(), any()))
            .thenReturn(new BigDecimal("100000"));   // ₹25,000 of exemption left

        MfRedemption r = redeem(400, "1000", "200"); // ₹1,00,000 gain

        // (1,00,000 − 25,000) × 12.5% = 9,375; + 4% cess = 9,750
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("9750.00");
    }

    @Test
    void aLossIsNeverTaxed() {
        MfRedemption r = redeem(400, "1000", "80");

        assertThat(r.getCapitalGain()).isEqualByComparingTo("-20000.00");
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a SIP redeemed in one go splits into STCG and LTCG by purchase lot")
    void fifoSplitsMixedHoldingPeriods() {
        LocalDate sellDate = LocalDate.of(2026, 6, 15);
        Holding h = Holding.builder().id(7L).symbol("PPFAS.MF").name("F")
            .quantity(BigDecimal.ZERO).averageCost(BigDecimal.ZERO)
            .buyDate(LocalDate.of(2024, 1, 10)).build();
        Transaction b1 = Transaction.builder().id(1L).holding(h)
            .type(Transaction.TransactionType.BUY).quantity(new BigDecimal("10"))
            .price(new BigDecimal("100")).transactionDate(LocalDate.of(2024, 1, 10)).build();
        Transaction b2 = Transaction.builder().id(2L).holding(h)
            .type(Transaction.TransactionType.BUY).quantity(new BigDecimal("10"))
            .price(new BigDecimal("150")).transactionDate(LocalDate.of(2026, 3, 1)).build();
        Transaction s1 = Transaction.builder().id(3L).holding(h)
            .type(Transaction.TransactionType.SELL).quantity(new BigDecimal("15"))
            .price(new BigDecimal("200")).transactionDate(sellDate).build();
        when(transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(7L))
            .thenReturn(List.of(b1, b2, s1));

        service.recordRedemption(USER, h, new BigDecimal("15"), new BigDecimal("200"),
            new BigDecimal("2000"), sellDate, 3L);

        ArgumentCaptor<MfRedemption> captor = ArgumentCaptor.forClass(MfRedemption.class);
        verify(redemptionRepo).save(captor.capture());
        MfRedemption r = captor.getValue();
        // 10 @ 100 → LT gain 1,000; 5 @ 150 → ST gain 250
        assertThat(r.getGainType()).isEqualTo("MIXED");
        assertThat(r.getLongTermGain()).isEqualByComparingTo("1000.00");
        assertThat(r.getShortTermGain()).isEqualByComparingTo("250.00");
        assertThat(r.getCapitalGain()).isEqualByComparingTo("1250.00");
        // ST 250×20% = 50; LT 1,000 within exemption → 0; cess on 50 = 52
        assertThat(r.getEstimatedTax()).isEqualByComparingTo("52.00");
    }
}
