package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class FdResponse {
    private Long id;
    private String bank;
    private BigDecimal principal;
    private BigDecimal rate;
    private String compounding;
    private boolean autoRenew;
    private LocalDate startDate;
    private LocalDate maturityDate;
    // computed
    private BigDecimal maturityValue;
    private BigDecimal currentValue;    // value accrued to today (principal + interest so far)
    private BigDecimal interestEarned;
    private Long daysToMaturity; // negative = matured
    private String status; // ACTIVE | CLOSED | MATURED_RENEWED
    private BigDecimal actualMaturityAmount;
    private LocalDate closedDate;
    // Present (non-null) only on the relevant side of a detected renewal — see
    // FixedDeposit.renewedToId/renewedFromId for what each means.
    private Long renewedToId;
    private Long renewedFromId;
}
