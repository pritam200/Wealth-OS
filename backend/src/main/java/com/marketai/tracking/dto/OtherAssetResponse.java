package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class OtherAssetResponse {
    private Long id;
    private String name;
    private String category;
    private BigDecimal value;
    private String note;
    private LocalDate asOf;
}
