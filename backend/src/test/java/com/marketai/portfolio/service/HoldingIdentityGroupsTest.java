package com.marketai.portfolio.service;

import com.marketai.portfolio.entity.Holding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingIdentityGroupsTest {

    private static Holding h(long id, String symbol, String isin) {
        return Holding.builder().id(id).symbol(symbol).name(symbol).isin(isin)
            .quantity(BigDecimal.ONE).averageCost(BigDecimal.TEN).build();
    }

    @Test
    void theSameIsinUnderTwoNamesIsOneSecurityAndDifferentPlansStayApart() {
        List<List<Holding>> groups = PortfolioService.identityGroups(List.of(
            h(1, "HDFC-MIDCAP-DIRECT-GROWTH.MF", "INF179K01XQ0"),
            h(2, "HDFC-MID-CAP-FUND-DIR-GR.MF", "INF179K01XQ0"),
            h(3, "HDFC-MIDCAP-REGULAR-GROWTH.MF", "INF179K01CR2"),   // different plan, different ISIN
            h(4, "HDFC-MIDCAP-DIRECT-GROWTH.MF", null)));             // same name as 1, no ISIN

        assertThat(groups).hasSize(2);
        assertThat(groups).anySatisfy(g -> assertThat(g).extracting(Holding::getId).containsExactlyInAnyOrder(1L, 2L, 4L));
        assertThat(groups).anySatisfy(g -> assertThat(g).extracting(Holding::getId).containsExactly(3L));
    }
}
