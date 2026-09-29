package com.marketai.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One disposal, flattened for ITR-filing tools (ClearTax/Quicko-style CSV import).
 *
 * <p>MF rows come from {@code MfRedemption}; equity rows from a first-in-first-out match of
 * each sale against its purchase lots ({@code FifoLedger}), one row per lot consumed.
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
