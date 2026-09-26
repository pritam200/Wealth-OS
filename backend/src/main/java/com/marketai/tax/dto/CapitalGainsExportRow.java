package com.marketai.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One disposal, flattened for ITR-filing tools (ClearTax/Quicko-style CSV import).
 *
 * <p>Sourced from {@code MfRedemption} — the one place holding-level LTCG/STCG splits are
 * actually persisted. Plain equity sales fall back to an undifferentiated Income row (see
 * {@code PortfolioService.sellHolding}) and so carry no acquisition date or gain-type split;
 * they are out of scope for this export until that changes.
 */
public record CapitalGainsExportRow(
    String assetSymbol,
    String assetName,
    String isin,
    LocalDate acquisitionDate,
    LocalDate saleDate,
    BigDecimal quantity,
    BigDecimal acquisitionValue,
    BigDecimal saleValue,
    String gainType,
    BigDecimal gainOrLoss,
    BigDecimal exemptionApplied
) {}
