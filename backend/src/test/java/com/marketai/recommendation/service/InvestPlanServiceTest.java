package com.marketai.recommendation.service;

import com.marketai.recommendation.service.InvestPlanService.FundPick;
import com.marketai.recommendation.service.InvestPlanService.Holdings;
import com.marketai.recommendation.service.InvestPlanService.Part;
import com.marketai.recommendation.service.InvestPlanService.Plan;
import com.marketai.recommendation.service.InvestPlanService.Risk;
import com.marketai.recommendation.service.InvestPlanService.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestPlanServiceTest {

    private static BigDecimal r(long v) { return BigDecimal.valueOf(v); }
    private static final Holdings NONE = new Holdings(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private static BigDecimal of(Plan p, Part part) {
        return p.steps().stream().filter(s -> s.part() == part).map(Step::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal total(Plan p) {
        return p.steps().stream().map(Step::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("Card dues are paid first, then the emergency fund is topped up, and only the rest is invested")
    void duesThenEmergencyThenInvest() {
        // ₹30k a month of spending, ₹50k liquid: ₹1.3L short of six months.
        Plan p = InvestPlanService.plan(r(500000), 7, Risk.MODERATE, r(30000), r(50000), r(20000), NONE, Map.of());
        assertThat(p.steps().get(0).part()).isEqualTo(Part.CARD_DUES);
        assertThat(of(p, Part.CARD_DUES)).isEqualByComparingTo("20000");
        assertThat(of(p, Part.EMERGENCY)).isEqualByComparingTo("130000");
        assertThat(total(p)).isEqualByComparingTo("500000");
        assertThat(p.disclaimer()).contains("not investment advice");
    }

    @Test
    @DisplayName("Money needed within three years stays mostly out of equity, whatever the risk")
    void shortHorizonIsMostlyDebt() {
        Plan p = InvestPlanService.plan(r(200000), 2, Risk.AGGRESSIVE, r(10000), r(1000000), BigDecimal.ZERO, NONE, Map.of());
        assertThat(of(p, Part.DEBT)).isEqualByComparingTo("150000");
        assertThat(of(p, Part.EQUITY_MIDSMALL)).isEqualByComparingTo("12000");
        assertThat(total(p)).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("New money goes first to what is below target, so nothing held has to be sold")
    void fillsTheUnderweightClass() {
        // ₹9L all in equity; target 65/25/10 after adding ₹1L means ₹2.5L debt and ₹1L gold are short.
        Holdings allEquity = new Holdings(r(900000), BigDecimal.ZERO, BigDecimal.ZERO);
        Plan p = InvestPlanService.plan(r(100000), 10, Risk.MODERATE, r(10000), r(1000000), BigDecimal.ZERO, allEquity, Map.of());
        assertThat(p.steps()).noneMatch(s -> s.part().name().startsWith("EQUITY"));
        assertThat(of(p, Part.DEBT)).isEqualByComparingTo("71429");
        assertThat(of(p, Part.GOLD)).isEqualByComparingTo("28571");
        assertThat(p.current().equity()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("The user's own funds are named in each part, and a large equity sum is staggered")
    void namesOwnFundsAndStaggers() {
        var core = List.of(new FundPick("Index A", "1", 14.0, 9.0, r(50000)));
        Plan p = InvestPlanService.plan(r(1000000), 10, Risk.AGGRESSIVE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NONE,
            Map.of(Part.EQUITY_CORE, core));
        assertThat(p.steps()).filteredOn(s -> s.part() == Part.EQUITY_CORE).singleElement()
            .satisfies(s -> assertThat(s.funds()).extracting(FundPick::name).containsExactly("Index A"));
        assertThat(p.notes()).anyMatch(n -> n.contains("STP"));
        assertThat(p.notes()).anyMatch(n -> n.contains("emergency fund could not be checked"));
        assertThat(total(p)).isEqualByComparingTo("1000000");
    }
}
