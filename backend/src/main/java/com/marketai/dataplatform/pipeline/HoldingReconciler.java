package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.IssueSeverity;
import com.marketai.dataplatform.domain.SuspectedCause;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the position the ledger implies with the one the institution reports, and — when they
 * differ — says which known explanations the evidence supports. It never changes either side.
 */
@Component
public class HoldingReconciler {

    /** What is known about the asset's ledger, used only to rank possible causes. */
    public record Evidence(List<BigDecimal> unconfirmedQuantities, List<BigDecimal> possibleDuplicateQuantities,
                           boolean hasSwitchLegs, boolean hasReinvestedDividends, boolean hasCorporateActions,
                           boolean hasManualEntries, LocalDate newestLedgerActivity, LocalDate reportedAsOf,
                           boolean incompleteEntries) {
        public static Evidence none() {
            return new Evidence(List.of(), List.of(), false, false, false, false, null, null, false);
        }
    }

    public record Result(boolean matches, BigDecimal difference, IssueSeverity severity, List<SuspectedCause> causes) {}

    public Result compare(BigDecimal calculated, BigDecimal reported, Evidence ev) {
        BigDecimal diff = reported.subtract(calculated);   // positive: the institution holds more
        if (diff.abs().compareTo(Tolerances.QTY_ABS) <= 0) return new Result(true, BigDecimal.ZERO, IssueSeverity.LOW, List.of());
        return new Result(false, diff, severity(diff, reported), causes(diff, ev));
    }

    /** Relative to the reported position: a holding that is wrong by over 0.5% is HIGH, over 0.05% MEDIUM. */
    static IssueSeverity severity(BigDecimal diff, BigDecimal reported) {
        if (reported.signum() == 0) return IssueSeverity.HIGH;
        BigDecimal rel = diff.abs().divide(reported.abs(), 6, RoundingMode.HALF_UP);
        if (rel.compareTo(new BigDecimal("0.005")) > 0) return IssueSeverity.HIGH;
        if (rel.compareTo(new BigDecimal("0.0005")) > 0) return IssueSeverity.MEDIUM;
        return IssueSeverity.LOW;
    }

    static List<SuspectedCause> causes(BigDecimal diff, Evidence ev) {
        List<SuspectedCause> out = new ArrayList<>();
        BigDecimal abs = diff.abs();
        // Units the ledger lacks (institution holds more), explained by a purchase we have not got.
        if (diff.signum() > 0) out.add(SuspectedCause.MISSING_TRANSACTION);
        // Units the ledger has too many of, explained by an unconfirmed or duplicated entry.
        if (diff.signum() < 0) {
            if (ev.possibleDuplicateQuantities().stream().anyMatch(q -> q.subtract(abs).abs().compareTo(Tolerances.QTY_ABS) <= 0))
                out.add(0, SuspectedCause.DUPLICATE_TRANSACTION);
            else if (!ev.possibleDuplicateQuantities().isEmpty()) out.add(SuspectedCause.DUPLICATE_TRANSACTION);
            if (ev.unconfirmedQuantities().stream().anyMatch(q -> q.subtract(abs).abs().compareTo(Tolerances.QTY_ABS) <= 0))
                out.add(0, SuspectedCause.SOURCE_ERROR);
        }
        if (ev.hasCorporateActions()) out.add(SuspectedCause.CORPORATE_ACTION);
        if (ev.hasSwitchLegs()) out.add(SuspectedCause.SWITCH);
        if (ev.hasReinvestedDividends()) out.add(SuspectedCause.DIVIDEND_REINVESTMENT);
        if (ev.hasManualEntries()) out.add(SuspectedCause.MANUAL_ADJUSTMENT);
        if (ev.incompleteEntries()) out.add(SuspectedCause.MISSING_TRANSACTION);
        if (ev.reportedAsOf() != null && ev.newestLedgerActivity() != null
                && ev.newestLedgerActivity().isAfter(ev.reportedAsOf())) out.add(SuspectedCause.DATA_DELAY);
        if (out.isEmpty()) out.add(SuspectedCause.SOURCE_ERROR);
        return out.stream().distinct().toList();
    }
}
