package com.marketai.tracking.dto;

import com.marketai.tracking.entity.InsurancePolicy.PolicyType;
import com.marketai.tracking.entity.InsurancePolicy.PremiumFrequency;
import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InsurancePolicyRequest {
    @NotNull private PolicyType policyType;
    @NotBlank private String insurer;
    private String policyNumber;
    private BigDecimal sumAssured;
    @NotNull @DecimalMin("1") private BigDecimal premiumAmount;
    private PremiumFrequency premiumFrequency = PremiumFrequency.ANNUAL;
    private LocalDate nextPremiumDueDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
}
