package com.marketai.tracking.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class FdRequest {
    @NotBlank private String bank;
    @NotNull @DecimalMin("1") private BigDecimal principal;
    @NotNull @DecimalMin("0.01") @DecimalMax("30") private BigDecimal rate;
    private String compounding = "quarterly";
    private boolean autoRenew = false;
    private LocalDate startDate;
    private LocalDate maturityDate;
}
