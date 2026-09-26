package com.marketai.rebalancing.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * The real tax consequence of selling a specific number of units of a specific holding, computed
 * from that holding's own FIFO lots via {@code DisposalCalculator} — never a flat-rate estimate.
 */
@Data
@Builder
public class TaxImpactDto {
    private BigDecimal unitsSold;
    private BigDecimal grossProceeds;
    private BigDecimal exitLoad;
    private BigDecimal shortTermGain;
    private BigDecimal longTermGain;
    private BigDecimal exemptionUsed;
    private BigDecimal tax;
    private BigDecimal netProceeds;
    private String deferralAdvice;
    /** Named limitations of this specific figure — e.g. surcharge or FY-exemption assumptions. */
    private List<String> caveats;
}
