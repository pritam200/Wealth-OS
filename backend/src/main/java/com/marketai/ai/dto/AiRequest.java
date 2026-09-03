package com.marketai.ai.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class AiRequest {
    @NotBlank
    @Size(max = 2000)
    private String prompt;

    private String symbol;   // optional, for stock-specific analysis
}
