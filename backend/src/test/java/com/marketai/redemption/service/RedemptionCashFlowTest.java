package com.marketai.redemption.service;

import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.entity.Reinvestment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REDEEMED_CASH / AVAILABLE-FOR-REINVESTMENT balance, exercised directly on the entity's
 * own arithmetic — the part that must stay correct as chunks are deployed one at a time.
 */
class RedemptionCashFlowTest {

    private MfRedemption redemption(String redeemed) {
        return MfRedemption.builder()
            .userId(1L).symbol("PPFAS.MF").fundName("Parag Parikh Flexi Cap")
            .redeemedAmount(new BigDecimal(redeemed))
            .reinvestedAmount(BigDecimal.ZERO)
            .redemptionDate(LocalDate.now())
            .status("ACTIVE")
            .reinvestments(new ArrayList<Reinvestment>())
            .build();
    }

    @Test
    void freshRedemption_hasItsFullAmountAwaitingRedeployment() {
        MfRedemption r = redemption("150000");
        assertThat(r.getCashRemaining()).isEqualByComparingTo("150000");
    }

    @Test
    void eachDeployedChunkReducesTheRemainingCash() {
        MfRedemption r = redemption("150000");

        r.setReinvestedAmount(r.getReinvestedAmount().add(new BigDecimal("30000")));
        assertThat(r.getCashRemaining()).isEqualByComparingTo("120000");

        r.setReinvestedAmount(r.getReinvestedAmount().add(new BigDecimal("45000")));
        assertThat(r.getCashRemaining()).isEqualByComparingTo("75000");

        r.setReinvestedAmount(r.getReinvestedAmount().add(new BigDecimal("75000")));
        assertThat(r.getCashRemaining()).isEqualByComparingTo("0");
    }

    @Test
    void overDeploymentNeverProducesNegativeCash() {
        MfRedemption r = redemption("100000");
        r.setReinvestedAmount(new BigDecimal("120000"));
        // Reinvesting more than was redeemed (topped up from elsewhere) must read as zero
        // remaining, not as a negative balance that would offset other redemptions.
        assertThat(r.getCashRemaining()).isEqualByComparingTo("0");
    }

    @Test
    void nullAmountsAreTreatedAsZeroRatherThanThrowing() {
        MfRedemption r = MfRedemption.builder().userId(1L).build();
        assertThat(r.getCashRemaining()).isEqualByComparingTo("0");
    }

    @Test
    void partialDeploymentIsNotRoundedAway() {
        MfRedemption r = redemption("100000.50");
        r.setReinvestedAmount(new BigDecimal("0.25"));
        assertThat(r.getCashRemaining()).isEqualByComparingTo("100000.25");
    }
}
