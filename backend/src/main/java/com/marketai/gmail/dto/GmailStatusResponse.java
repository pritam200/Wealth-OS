package com.marketai.gmail.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class GmailStatusResponse {
    private boolean connected;
    private String connectedEmail;
    private LocalDateTime lastSyncAt;
    private long importedCount;
}
