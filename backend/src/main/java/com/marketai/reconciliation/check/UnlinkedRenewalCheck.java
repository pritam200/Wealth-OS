package com.marketai.reconciliation.check;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.tracking.dto.FdResponse;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects a matured deposit that looks like it was rolled over into a new one, without the two
 * being linked — the shape of a silent net-worth double count.
 *
 * <p>Net worth already excludes {@code CLOSED} and {@code MATURED_RENEWED} rows, so a renewal
 * that <em>links</em> is counted once and is correct. The danger is the renewal that <em>fails
 * to link</em>: {@code detectAndLinkRenewal} matches on same bank, maturity within 10 days of
 * the new start date, and principal within 5% of the matured value. Miss any of those — the
 * bank spells its name differently on the new advice, the rollover is booked a fortnight late,
 * TDS pushes the principal further than 5% off — and both rows stay visible. A ₹10,64,000
 * maturity rolled into a ₹10,64,000 new deposit then reads as ₹21,28,000.
 *
 * <p>Nothing caught that. {@code MATURED_IDLE} only fires after 30 days and never looks for a
 * successor, so for a month the double count is completely silent, and after that the message
 * says "record what happened to it" rather than "this money is being counted twice, right now".
 *
 * <p>Deliberately wider than the linking tolerances — 45 days and 25% against 10 days and 5%.
 * The linker has already tried and failed at the tight bounds; the job here is to catch the
 * near-misses it rejected. Because those bounds are loose, this <b>reports and never links</b>:
 * per the spec's "never guess", an ambiguous pair is escalated for review, not auto-merged.
 */
@Component
@RequiredArgsConstructor
public class UnlinkedRenewalCheck implements ReconciliationCheck {

    /** Wider than the linker's 10 days — a rollover booked late still needs flagging. */
    static final int LOOKAHEAD_DAYS = 45;

    /** Wider than the linker's 5% — TDS and partial withdrawal move the principal further. */
    static final double AMOUNT_TOLERANCE = 0.25;

    private final TrackingService trackingService;

    @Override public String id() { return "FD_UNLINKED_RENEWAL"; }
    @Override public String domain() { return "FD"; }
    @Override public String description() {
        return "A matured deposit and a similar new one at the same bank are not linked, so the "
             + "same money may be counted twice in net worth";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<FdResponse> fds = trackingService.listFds(userId);
        List<ReconciliationIssue> issues = new ArrayList<>();

        for (FdResponse matured : fds) {
            if (!isUnresolvedMatured(matured)) continue;

            BigDecimal maturedValue = matured.getMaturityValue() != null
                ? matured.getMaturityValue() : matured.getPrincipal();
            if (maturedValue == null || maturedValue.signum() <= 0) continue;
            if (matured.getMaturityDate() == null) continue;

            for (FdResponse successor : fds) {
                if (!isCandidateSuccessor(matured, successor)) continue;

                long gap = ChronoUnit.DAYS.between(matured.getMaturityDate(), successor.getStartDate());
                if (gap < 0 || gap > LOOKAHEAD_DAYS) continue;

                BigDecimal drift = successor.getPrincipal().subtract(maturedValue).abs()
                    .divide(maturedValue, 6, RoundingMode.HALF_UP);
                if (drift.doubleValue() > AMOUNT_TOLERANCE) continue;

                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type(id()).severity("HIGH")
                    .referenceId(matured.getId())
                    .description(String.format(
                        "%s FD #%d matured at %s on %s, and FD #%d opened %d day(s) later with a "
                            + "principal of %s. These look like the same money rolled over, but they "
                            + "are not linked — so both are counted and net worth may be overstated "
                            + "by about %s. Confirm whether #%d was renewed into #%d.",
                        matured.getBank(), matured.getId(), inr(maturedValue),
                        matured.getMaturityDate(), successor.getId(), gap,
                        inr(successor.getPrincipal()), inr(maturedValue),
                        matured.getId(), successor.getId()))
                    .build());
                // One finding per matured deposit: listing every candidate would bury the signal.
                break;
            }
        }
        return issues;
    }

    /** Matured (or past maturity) and not already resolved by a link or closure. */
    private boolean isUnresolvedMatured(FdResponse fd) {
        if (fd.getRenewedToId() != null) return false;
        if ("CLOSED".equalsIgnoreCase(fd.getStatus())
            || "MATURED_RENEWED".equalsIgnoreCase(fd.getStatus())) return false;

        // Covers both the MATURED status and an ACTIVE row whose maturity has simply passed —
        // the daily sweep may not have run yet, and the double count is live either way.
        return "MATURED".equalsIgnoreCase(fd.getStatus())
            || (fd.getDaysToMaturity() != null && fd.getDaysToMaturity() <= 0);
    }

    private boolean isCandidateSuccessor(FdResponse matured, FdResponse successor) {
        if (successor.getId().equals(matured.getId())) return false;
        if (successor.getRenewedFromId() != null) return false;      // already linked elsewhere
        if (successor.getStartDate() == null || successor.getPrincipal() == null) return false;
        if (successor.getPrincipal().signum() <= 0) return false;
        if ("CLOSED".equalsIgnoreCase(successor.getStatus())
            || "MATURED_RENEWED".equalsIgnoreCase(successor.getStatus())) return false;

        // Same bank, compared leniently: the linker's exact-ignore-case match is part of why
        // these pairs go unlinked in the first place ("HDFC Bank" vs "HDFC Bank Ltd").
        return normaliseBank(matured.getBank()).equals(normaliseBank(successor.getBank()));
    }

    /** Strips the corporate suffixes and punctuation that make two spellings of one bank differ. */
    static String normaliseBank(String bank) {
        if (bank == null) return "";
        return bank.toLowerCase()
            .replaceAll("\\b(ltd|limited|bank|of|india|the)\\b", "")
            .replaceAll("[^a-z0-9]", "")
            .trim();
    }

    private static String inr(BigDecimal v) {
        return "₹" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
