package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A position as the institution reports it. */
@Value @Builder(toBuilder = true)
public class NormalizedHolding {
    AccountRef account;
    AssetRef asset;
    BigDecimal quantity;
    BigDecimal averageCost;
    BigDecimal investedValue;
    BigDecimal currentPrice;
    BigDecimal currentValue;
    BigDecimal xirr;
    LocalDate asOfDate;
    SourceType sourceType;
    String sourceProvider;
    @Builder.Default double recordConfidence = 1.0;
}
