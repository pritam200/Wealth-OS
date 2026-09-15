package com.marketai.actions.dto;

import com.marketai.actions.entity.ActionStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Records what the user did with a recommended action. Identity fields (actionType/symbol/
 * actionDate) are present because the queue is recomputed each load — the row may not exist
 * yet the first time the user touches a given recommendation.
 */
@Data
public class ActionUpdateRequest {
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
}
