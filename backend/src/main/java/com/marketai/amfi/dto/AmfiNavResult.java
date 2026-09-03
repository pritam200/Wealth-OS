package com.marketai.amfi.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class AmfiNavResult {
    private String schemeName;
    private String schemeCode;
    private BigDecimal nav;
    private LocalDate asOf;

    /** AMFI's SEBI category, e.g. "Equity Scheme - Large Cap Fund". Null if the file had none. */
    private String category;

    /** "Open Ended" | "Close Ended" | "Interval". Null when AMFI's section header omitted it. */
    private String schemeType;

    /** Fund house, e.g. "SBI Mutual Fund". Null when no AMC line preceded the row. */
    private String amc;

    /** Normalised bucket derived from {@link #category}; OTHER when unclear. Never null. */
    private MfCategoryBucket categoryBucket;
}
