package com.marketai.advisor.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One ledger record an answer rests on. {@code kind} and {@code id} open the record's source
 * (GET /api/provenance/{kind}/{id}); kind is null for rows that have no source view of their
 * own (a plan line, a reconciliation issue).
 */
@Value
@Builder
public class AdvisorEvidence {
    String kind;
    Long id;
    LocalDate date;
    String label;
    BigDecimal amount;
    /** Where the record came from, e.g. "Email from alerts@hdfcbank.net", "Entered by hand". */
    String source;
}
