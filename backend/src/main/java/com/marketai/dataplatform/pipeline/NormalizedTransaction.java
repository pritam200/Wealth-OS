package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.domain.TransactionType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A transaction in the platform's own terms, whatever source and schema it arrived in. */
@Value @Builder(toBuilder = true)
public class NormalizedTransaction {
    AccountRef account;
    AssetRef asset;
    TransactionType type;
    LocalDate transactionDate;
    LocalDate settlementDate;
    BigDecimal quantity;
    BigDecimal unitPrice;
    BigDecimal grossAmount;
    BigDecimal fees;
    BigDecimal taxes;
    BigDecimal netAmount;
    @Builder.Default String currency = "INR";
    BigDecimal ratioFrom;
    BigDecimal ratioTo;
    /** MERGER: the surviving company's symbol. */
    String newSymbol;
    /** FD_CREATION: rate in percent and maturity date. */
    BigDecimal interestRate;
    LocalDate maturityDate;
    SourceType sourceType;
    String sourceProvider;
    /** The source's own identifier for the event: trade number, UTR, aggregator transaction id. */
    String sourceReference;
    LocalDateTime sourceTimestamp;
    /** How sure the source/extractor is of this particular record, 0..1. Structured feeds use 1. */
    @Builder.Default double recordConfidence = 1.0;
    String linkGroup;
    String notes;
    /** The source reports this event as cancelled or reversed. */
    boolean reversal;
}
