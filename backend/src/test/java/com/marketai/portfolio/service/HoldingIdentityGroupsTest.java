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

    private static Holding at(long id, String symbol, String isin, String broker) {
        Holding h = h(id, symbol, isin);
        h.setBroker(broker);
        return h;
    }

    @Test
    void theSameStockAtTwoBrokersIsTwoPositionsNeverOneDuplicate() {
        // Jio Financial at Mirae and at Kotak: both real, neither a copy of the other.
        List<List<Holding>> groups = PortfolioService.identityGroups(List.of(
            at(1, "JIOFIN", "INE758E01017", "Mirae Asset"),
            at(2, "JIOFIN", "INE758E01017", "Kotak Securities")));

        assertThat(groups).hasSize(2);
        assertThat(groups).allSatisfy(g -> assertThat(g).hasSize(1));
    }

    @Test
    void theSameStockUnderOneBrokerAcrossPortfoliosIsStillADuplicate() {
        List<List<Holding>> groups = PortfolioService.identityGroups(List.of(
            at(1, "JIOFIN", "INE758E01017", "Kotak Securities"),
            at(2, "JIOFIN", "INE758E01017", " kotak securities ")));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).extracting(Holding::getId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void anExchangeLabelOrBlankNamesNoBrokerSoItStaysWithTheOneRealBroker() {
        // The original incident: one account imported once as "NSE" and once as "MStock".
        List<List<Holding>> same = PortfolioService.identityGroups(List.of(
            at(1, "TITAN.NS", null, "NSE"), at(2, "TITAN.NS", null, "MStock"), at(3, "TITAN.NS", null, null)));
        assertThat(same).hasSize(1);

        // With two real brokers present, the unlabelled row cannot be placed and is left alone.
        List<List<Holding>> split = PortfolioService.identityGroups(List.of(
            at(1, "PFC", "INE134E01011", "Mirae Asset"), at(2, "PFC", "INE134E01011", "Kotak Securities"),
            at(3, "PFC", "INE134E01011", null)));
        assertThat(split).hasSize(3);
    }
}
