package com.marketai.card.route;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Whether a card can be used for a given payment at all, before any reward maths.
 *
 * <p>This is a correctness gate, not a ranking input. The card-optimisation research found three
 * independent constraints that all sit <em>in front of</em> the reward rate:
 *
 * <ol>
 *   <li><b>Network</b> — only RuPay works on UPI.</li>
 *   <li><b>Transaction type</b> — NPCI blocks P2P, P2PM, C2C, ATM and merchant cash withdrawal,
 *       digital account opening, lending platforms, eRUPI, IPO, foreign inward remittance and
 *       mutual funds on credit-card-on-UPI.</li>
 *   <li><b>Acquirer MCC blocks</b> — financial institutions, ATMs, crypto/forex, securities,
 *       wire transfer, debt collection, lending and gambling.</li>
 * </ol>
 */
public final class RouteEligibility {

    /** Transaction types NPCI does not permit on credit-card-on-UPI. */
    public static final Set<String> BLOCKED_UPI_TXN_TYPES = Set.of(
        "P2P", "P2PM", "C2C", "ATM_WITHDRAWAL", "MERCHANT_CASH_WITHDRAWAL",
        "DIGITAL_ACCOUNT_OPENING", "LENDING", "ERUPI", "IPO",
        "FOREIGN_INWARD_REMITTANCE", "MUTUAL_FUND");

    /** MCCs acquirers block on credit-card-on-UPI. */
    public static final Set<String> BLOCKED_UPI_MCCS = Set.of(
        "6010", "6012", "6013",   // financial institutions
        "6011",                    // ATM
        "6051",                    // crypto / forex
        "6211",                    // securities
        "4829",                    // wire transfer
        "7322",                    // debt collection
        "7400", "7401", "7402",   // lending / digital account
        "7800", "7801", "7802", "7995", "9406"); // gambling / lottery

    /** Per-transaction and daily caps on credit-card-on-UPI. */
    public static final BigDecimal UPI_PER_TXN_LIMIT = new BigDecimal("500000");
    public static final BigDecimal UPI_DAILY_LIMIT = new BigDecimal("1000000");

    private RouteEligibility() {}

    public record Verdict(boolean eligible, String reason) {
        public static Verdict allow() { return new Verdict(true, null); }
        public static Verdict deny(String reason) { return new Verdict(false, reason); }
    }

    public static Verdict check(CardNetwork network, PaymentRoute route,
                                String mcc, String txnType, BigDecimal amount) {
        if (route != PaymentRoute.UPI_P2M) {
            return Verdict.allow();
        }
        if (network == null || !network.supportsUpi()) {
            return Verdict.deny(String.format(
                "%s cards cannot be linked to UPI — only RuPay credit cards can",
                network == null ? "This" : network.name()));
        }
        if (txnType != null && BLOCKED_UPI_TXN_TYPES.contains(txnType.toUpperCase())) {
            return Verdict.deny(
                "Credit card on UPI does not permit " + txnType + " transactions");
        }
        if (mcc != null && BLOCKED_UPI_MCCS.contains(mcc.trim())) {
            return Verdict.deny("Merchant category " + mcc + " is blocked for credit card on UPI");
        }
        if (amount != null && amount.compareTo(UPI_PER_TXN_LIMIT) > 0) {
            return Verdict.deny(String.format(
                "Above the ₹%s per-transaction limit for credit card on UPI",
                UPI_PER_TXN_LIMIT.toPlainString()));
        }
        return Verdict.allow();
    }
}
