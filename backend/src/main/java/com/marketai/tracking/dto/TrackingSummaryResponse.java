package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class TrackingSummaryResponse {
    private List<FdResponse> fds;
    private List<RdResponse> rds;
    private List<LoanResponse> loans;
    private List<OtherAssetResponse> otherAssets;
    private List<EpfResponse> epfAccounts;
    // aggregates
    private BigDecimal totalFdPrincipal;
    private BigDecimal totalFdMaturityValue;
    private BigDecimal totalFdCurrentValue;
    private BigDecimal totalRdCorpus;
    private BigDecimal totalRdCurrentValue;
    private BigDecimal totalOtherAssets;
    private BigDecimal totalEpf;
    private BigDecimal totalLoanOutstanding;
    private BigDecimal totalMonthlyEmi;
}
