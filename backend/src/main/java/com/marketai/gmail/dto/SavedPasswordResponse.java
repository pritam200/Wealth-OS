package com.marketai.gmail.dto;

import com.marketai.gmail.entity.SavedPdfPassword;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

// Never includes the password itself, encrypted or otherwise — this is what the
// "Manage Saved Passwords" screen renders.
@Data
@Builder
public class SavedPasswordResponse {
    private Long id;
    private String providerKey;
    private String passwordHint;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;

    public static SavedPasswordResponse from(SavedPdfPassword p) {
        return SavedPasswordResponse.builder()
                .id(p.getId())
                .providerKey(p.getProviderKey())
                .passwordHint(p.getPasswordHint())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .lastUsedAt(p.getLastUsedAt())
                .build();
    }
}
