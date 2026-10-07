package com.marketai.onboarding;

import com.marketai.dataplatform.domain.AssetClass;

/** The kinds of place a user's money lives, as the setup checklist groups them. */
public enum SourceKind {
    STOCKS("Stocks & demat holdings", AssetClass.STOCK, 35),
    MUTUAL_FUNDS("Mutual funds", AssetClass.MUTUAL_FUND, 35),
    FIXED_DEPOSITS("Fixed deposits", AssetClass.FD, 90),
    RECURRING_DEPOSITS("Recurring deposits", AssetClass.RD, 35),
    CREDIT_CARDS("Credit cards", null, 35);

    private final String label;
    private final AssetClass assetClass;
    private final int refreshEveryDays;

    SourceKind(String label, AssetClass assetClass, int refreshEveryDays) {
        this.label = label;
        this.assetClass = assetClass;
        this.refreshEveryDays = refreshEveryDays;
    }

    public String label() { return label; }
    /** The asset class a file import books into; null when files can't be imported for this kind. */
    public AssetClass assetClass() { return assetClass; }
    /** How old the "synced through" date may get before the source is flagged as out of date. */
    public int refreshEveryDays() { return refreshEveryDays; }
    public boolean fileImportable() { return assetClass != null; }
}
