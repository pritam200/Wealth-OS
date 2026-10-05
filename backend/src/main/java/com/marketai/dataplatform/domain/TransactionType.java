package com.marketai.dataplatform.domain;

public enum TransactionType {
    BUY(Family.PURCHASE, 1), SELL(Family.SALE, -1), SIP(Family.PURCHASE, 1), STP(Family.PURCHASE, 1),
    SWP(Family.SALE, -1), SWITCH_IN(Family.PURCHASE, 1), SWITCH_OUT(Family.SALE, -1),
    DIVIDEND(Family.INCOME, 0), INTEREST(Family.INCOME, 0), BONUS(Family.CORPORATE_ACTION, 1),
    SPLIT(Family.CORPORATE_ACTION, 0), MERGER(Family.CORPORATE_ACTION, 0),
    REDEMPTION(Family.SALE, -1), MATURITY(Family.SALE, -1), FD_CREATION(Family.DEPOSIT_OPEN, 0),
    FD_MATURITY(Family.DEPOSIT_CLOSE, 0), DEPOSIT(Family.CASH_IN, 0), WITHDRAWAL(Family.CASH_OUT, 0),
    EMI(Family.CASH_OUT, 0), FEE(Family.CASH_OUT, 0), TAX(Family.CASH_OUT, 0),
    CREDIT(Family.CASH_IN, 0), DEBIT(Family.CASH_OUT, 0), TRANSFER(Family.TRANSFER, 0), OTHER(Family.OTHER, 0);

    /**
     * Types that describe the same kind of event. An email may call a purchase a SIP where the
     * institution calls it a BUY; they are the same event, so they must be allowed to match.
     * Matching still demands the same asset, date and size, so a family alone never merges anything.
     */
    public enum Family { PURCHASE, SALE, INCOME, CORPORATE_ACTION, DEPOSIT_OPEN, DEPOSIT_CLOSE, CASH_IN, CASH_OUT, TRANSFER, OTHER }

    private final Family family;
    private final int unitEffect;

    TransactionType(Family family, int unitEffect) { this.family = family; this.unitEffect = unitEffect; }

    public Family family() { return family; }

    /** +1 adds units to a holding, -1 removes them, 0 leaves the unit count alone (SPLIT scales it). */
    public int unitEffect() { return unitEffect; }

    public boolean movesUnits() { return unitEffect != 0; }
}
