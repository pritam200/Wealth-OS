package com.marketai.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiRequest {
    @NotBlank
    @Size(max = 2000)
    private String prompt;

    private String symbol;   // optional, for stock-specific analysis
}
