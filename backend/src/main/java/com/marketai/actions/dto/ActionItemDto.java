package com.marketai.actions.dto;

import com.marketai.actions.entity.ActionItem;
import com.marketai.actions.entity.ActionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
public class ActionItemDto {
    private Long id;
    private String actionType;
    private String symbol;
    private String name;
    private String assetType;
    private BigDecimal amount;
    private BigDecimal quantity;
    private ActionStatus status;
    private String note;
    private LocalDate snoozedUntil;
    private LocalDate actionDate;
    private LocalDateTime updatedAt;

    public static ActionItemDto from(ActionItem a) {
        return ActionItemDto.builder()
            .id(a.getId())
            .actionType(a.getActionType())
            .symbol(a.getSymbol())
            .name(a.getName())
            .assetType(a.getAssetType())
            .amount(a.getAmount())
            .quantity(a.getQuantity())
            .status(a.getStatus())
            .note(a.getNote())
            .snoozedUntil(a.getSnoozedUntil())
            .actionDate(a.getActionDate())
            .updatedAt(a.getUpdatedAt())
            .build();
    }
}
