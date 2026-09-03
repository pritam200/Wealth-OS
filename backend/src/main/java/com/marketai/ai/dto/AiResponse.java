package com.marketai.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AiResponse {
    private String summary;
    private List<String> risks;
    private List<String> opportunities;
    private String signal;
    private String rawResponse;
    private LocalDateTime generatedAt;
}
