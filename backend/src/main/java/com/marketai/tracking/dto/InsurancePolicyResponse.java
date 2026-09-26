package com.marketai.tracking.dto;

import com.marketai.tracking.entity.InsurancePolicy.PolicyType;
import com.marketai.tracking.entity.InsurancePolicy.PremiumFrequency;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class InsurancePolicyResponse {
    private Long id;
    private PolicyType policyType;
    private String insurer;
    private String policyNumber;
    private BigDecimal sumAssured;
    private BigDecimal premiumAmount;
    private PremiumFrequency premiumFrequency;
    private LocalDate nextPremiumDueDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
    private String status; // ACTIVE | LAPSED | CLOSED
    // computed
    private Long daysToNextPremium; // negative = overdue
}
