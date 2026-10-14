package com.marketai.card.service;

import com.marketai.card.service.CardStatementAnalyzer.Flag;
import com.marketai.card.service.CardStatementAnalyzer.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardStatementAnalyzerTest {

    private static Line debit(String merchant, String amount, String date) {
        return new Line(merchant, new BigDecimal(amount), LocalDate.parse(date), "INR", true, merchant);
    }

    private static List<String> types(List<Flag> flags) {
        return flags.stream().map(Flag::type).toList();
    }

    @Test
    void flagsLateFeeInterestAndGstOnTheirOwnWording() {
        var flags = CardStatementAnalyzer.analyze(List.of(
            debit("LATE PAYMENT FEE", "1000", "2026-09-10"),
            debit("FINANCE CHARGES", "845.50", "2026-09-10"),
            debit("IGST", "333.09", "2026-09-10"),
            debit("Swiggy", "450", "2026-09-05")), null, null);
        assertThat(types(flags)).containsExactly("LATE_FEE", "INTEREST", "GST");
    }

    @Test
    void aShopWithChargesInItsEvidenceIsNotABankCharge() {
        Line l = new Line("Amazon", new BigDecimal("999"), LocalDate.parse("2026-09-05"), "INR", true,
            "05/09 Amazon 999.00 free delivery charges waived");
        assertThat(CardStatementAnalyzer.analyze(List.of(l), null, null)).isEmpty();
    }

    @Test
    void flagsASameDayDoubleChargeButNotADoubleFee() {
        var flags = CardStatementAnalyzer.analyze(List.of(
            debit("Zomato", "320", "2026-09-05"), debit("Zomato", "320", "2026-09-05"),
            debit("GST", "18", "2026-09-06"), debit("GST", "18", "2026-09-06")), null, null);
        assertThat(types(flags)).contains("DUPLICATE_CHARGE").doesNotContain("LARGE_TRANSACTION");
        assertThat(flags.stream().filter(f -> f.type().equals("DUPLICATE_CHARGE"))).hasSize(1);
    }

    @Test
    void flagsALargePurchaseAgainstTheStatementsOwnNorm() {
        var flags = CardStatementAnalyzer.analyze(List.of(
            debit("A", "500", "2026-09-01"), debit("B", "700", "2026-09-02"), debit("C", "900", "2026-09-03"),
            debit("D", "600", "2026-09-04"), debit("Jewellers", "180000", "2026-09-05")), null, null);
        assertThat(types(flags)).containsExactly("LARGE_TRANSACTION");
    }

    @Test
    void flagsForeignCurrencyAndOverLimit() {
        Line usd = new Line("Netflix", new BigDecimal("649"), LocalDate.parse("2026-09-02"), "USD", true, "Netflix USD 7.99");
        var flags = CardStatementAnalyzer.analyze(List.of(usd), new BigDecimal("100000"), new BigDecimal("120000"));
        assertThat(types(flags)).contains("FOREIGN_CURRENCY", "OVER_LIMIT");
        assertThat(flags.get(0).severity()).isEqualTo(CardStatementAnalyzer.ALERT);
    }

    @Test
    void jsonRoundTripsAndIgnoresGarbage() {
        var flags = CardStatementAnalyzer.analyze(List.of(debit("LATE PAYMENT FEE", "1000", "2026-09-10")), null, null);
        assertThat(CardStatementAnalyzer.fromJson(CardStatementAnalyzer.toJson(flags))).isEqualTo(flags);
        assertThat(CardStatementAnalyzer.fromJson("not json")).isEmpty();
        assertThat(CardStatementAnalyzer.fromJson(null)).isEmpty();
    }

    @Test
    void storedFlagsComeBackWithAMismatchFirstAndChargesAreToldApartFromUnusualItems() {
        var flags = CardStatementAnalyzer.analyze(List.of(
            debit("LATE PAYMENT FEE", "1000", "2026-09-10"),
            debit("Zomato", "320", "2026-09-05"), debit("Zomato", "320", "2026-09-05")), null, null);
        var st = com.marketai.card.entity.CardStatement.builder()
            .flagsJson(CardStatementAnalyzer.toJson(flags))
            .arithmeticMismatch(true).arithmeticMismatchDetail("off by ₹900").build();

        var out = CardStatementAnalyzer.flagsFor(st);
        assertThat(out.get(0).type()).isEqualTo("ARITHMETIC_MISMATCH");
        assertThat(out.stream().filter(CardStatementAnalyzer::isBankCharge).map(Flag::type)).containsExactly("LATE_FEE");
        assertThat(out.stream().filter(f -> !CardStatementAnalyzer.isBankCharge(f)).map(Flag::type))
            .containsExactlyInAnyOrder("ARITHMETIC_MISMATCH", "DUPLICATE_CHARGE");
    }
}
