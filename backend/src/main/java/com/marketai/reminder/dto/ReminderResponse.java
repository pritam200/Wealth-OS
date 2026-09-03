package com.marketai.reminder.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReminderResponse {
    private String type;      // FD_MATURITY | RD_INSTALLMENT | CARD_BILL | LOAN_EMI
    private String title;
    private String subtitle;
    private LocalDate dueDate;
    private long daysUntil;   // negative = overdue
    private BigDecimal amount;
    private String severity;  // OVERDUE | DUE_SOON | UPCOMING
}
