package com.marketai.advisor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdvisorAskRequest {
    @NotBlank
    @Size(max = 500)
    private String question;
}
