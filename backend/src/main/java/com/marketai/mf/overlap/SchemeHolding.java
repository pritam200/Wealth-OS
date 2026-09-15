package com.marketai.mf.overlap;

import java.math.BigDecimal;

/**
 * One stock inside one mutual fund scheme, as disclosed in the AMC's monthly portfolio.
 *
 * <p>ISIN is the join key. Scheme portfolios name the same company differently across AMCs
 * ("Reliance Industries Ltd", "Reliance Inds.", "RELIANCE INDUSTRIES LIMITED"), so matching on
 * name would under-report overlap — silently, and in the direction that makes a portfolio look
 * better diversified than it is.
 *
 * @param pctToNav weight as a percentage of NAV, exactly as disclosed
 * @param quantity units held, kept so weights can be re-priced rather than used stale
 */
public record SchemeHolding(String isin, String instrumentName, String industry,
                            BigDecimal quantity, BigDecimal pctToNav) {}
