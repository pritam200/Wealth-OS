package com.marketai.ai.review.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A human's decision on a queued email. For ACCEPT the corrected* fields are ignored; for
 * EDIT they replace what the model proposed before the record is booked.
 */
@Data
public class ReviewDecisionRequest {
    /** ACCEPT | EDIT | REJECT */
    private String decision;

    private String correctedType;      // an EmailIntelType name
    private BigDecimal correctedAmount;
    private LocalDate correctedDate;
    private String correctedCounterparty;
    private String correctedCategory;
    private String note;
}
