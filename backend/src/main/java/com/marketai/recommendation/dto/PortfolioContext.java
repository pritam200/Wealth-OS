package com.marketai.recommendation.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Whole-portfolio state, computed once and passed into per-security analysis so a
 * recommendation can account for what the user already owns instead of judging each
 * security in isolation (e.g. not suggesting "accumulate" on a stock that is already 30%
 * of the portfolio, or on a sector the portfolio is already heavily tilted toward).
 *
 * Every field that could not be established from verified data is null and named in
 * {@link #dataGaps} — nothing here is estimated to fill a hole.
 */
@Data
@Builder
public class PortfolioContext {

    /* ── Asset-class values (absolute) ─────────────────────────── */
    private BigDecimal stocksValue;
    private BigDecimal mfValue;
    private BigDecimal fdValue;
    private BigDecimal rdValue;
    private BigDecimal epfValue;
    private BigDecimal otherAssetsValue;
    private BigDecimal loansOutstanding;
    private BigDecimal totalAssets;
    private BigDecimal netWorth;

    /* ── Asset-class mix (% of total assets) ───────────────────── */
    private Double equityPercent;   // direct stocks + equity-oriented MF
    private Double debtPercent;     // FD + RD + EPF
    private Double otherPercent;

    /* ── Equity book ───────────────────────────────────────────── */
    private BigDecimal equityInvested;
    private BigDecimal equityCurrent;
    private BigDecimal equityPnl;
    private Double equityPnlPercent;
    private int stockCount;
    private int mfCount;

    /* ── Concentration ─────────────────────────────────────────── */
    /** Largest direct-stock positions, descending by value. */
    private List<Exposure> topStockExposures;
    /** Sector exposure across DIRECT STOCKS ONLY — see sectorScopeNote. */
    private List<Exposure> sectorExposures;
    private List<Flag> concentrationFlags;

    /* ── Truthfulness markers ──────────────────────────────────── */
    /**
     * Share of direct-stock value whose sector is actually known. Sector is populated
     * lazily per symbol, so this is frequently below 100% and the sector picture is
     * correspondingly partial — callers must surface this rather than presenting sector
     * percentages as complete.
     */
    private Double sectorCoveragePercent;
    /** Plain-English scope limit for the sector figures. */
    private String sectorScopeNote;
    /** FULL when every input was available; PARTIAL when something material was missing. */
    private String dataQuality;
    /** Named, specific gaps — rendered to the user instead of silently omitted analysis. */
    private List<String> dataGaps;

    @Data
    @Builder
    public static class Exposure {
        private String label;              // symbol/fund name, or sector name
        private BigDecimal value;
        private Double percentOfTotalAssets;
        private Double percentOfEquity;
        private int holdingCount;
        private String sector;             // null for sector rows and unknown-sector stocks
    }

    @Data
    @Builder
    public static class Flag {
        private String type;      // SINGLE_STOCK | SECTOR | ASSET_ALLOCATION | LEVERAGE
        private String label;
        private Double percent;
        private String severity;  // HIGH | MODERATE
        private String message;
    }
}
