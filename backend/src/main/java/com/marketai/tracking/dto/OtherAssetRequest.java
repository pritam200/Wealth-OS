package com.marketai.tracking.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OtherAssetRequest {
    @NotBlank private String name;
    @NotBlank private String category;
    @NotNull @DecimalMin("0") private BigDecimal value;
    private String note;
    private LocalDate asOf;
}
