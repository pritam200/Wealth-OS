package com.marketai.card.route;

/**
 * Card networks, and whether each can be used on UPI.
 *
 * <p>NPCI permits only RuPay credit cards on UPI. This is a hard eligibility fact, not a
 * preference — a Visa or Mastercard credit card physically cannot complete a UPI payment. A
 * recommendation engine that suggests one for a UPI transaction is not giving imperfect advice;
 * it is giving impossible advice.
 */
public enum CardNetwork {
    RUPAY(true),
    VISA(false),
    MASTERCARD(false),
    AMEX(false),
    DINERS(false);

    private final boolean upiEligible;

    CardNetwork(boolean upiEligible) { this.upiEligible = upiEligible; }

    /** Source: NPCI / issuer FAQs — "Currently, only RuPay Credit Cards can be linked on UPI". */
    public boolean supportsUpi() { return upiEligible; }
}
