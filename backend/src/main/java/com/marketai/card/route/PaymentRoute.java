package com.marketai.card.route;

/** How a payment is presented to the merchant. Determines which cards are even usable. */
public enum PaymentRoute {
    /** Card number entered or swiped. */
    CARD_NUMBER,
    /** Tokenised card, e.g. saved on a merchant site. */
    TOKEN,
    /** Credit card linked to UPI, paying a merchant. */
    UPI_P2M
}
