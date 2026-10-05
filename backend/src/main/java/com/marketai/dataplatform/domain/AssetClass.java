package com.marketai.dataplatform.domain;

public enum AssetClass {
    STOCK, MUTUAL_FUND, ETF, BOND, FD, RD, PPF, EPF, NPS, SGB, GOLD, SILVER, INSURANCE, US_STOCK,
    FNO, PMS, AIF, REAL_ESTATE, LOAN, CASH, OTHER;

    /** Whether the asset is held as a countable number of units. FD/loan/cash are amounts. */
    public boolean unitBased() {
        return switch (this) {
            case STOCK, MUTUAL_FUND, ETF, BOND, SGB, GOLD, SILVER, US_STOCK, FNO, PMS, AIF -> true;
            default -> false;
        };
    }
}
