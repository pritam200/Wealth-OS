package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** What one source said about a transaction. The reconciliation rules read only these. */
public record SourceObservation(SourceType type, String provider, String reference, LocalDateTime timestamp,
                                LocalDate date, BigDecimal quantity, BigDecimal unitPrice, BigDecimal gross,
                                BigDecimal fees, BigDecimal taxes, BigDecimal net, double recordConfidence) {

    public int rank() { return type.rank(); }
}
