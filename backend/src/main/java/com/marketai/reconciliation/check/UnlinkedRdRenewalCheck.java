package com.marketai.reconciliation.check;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.tracking.dto.RdResponse;
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
 * The recurring-deposit counterpart of {@link UnlinkedRenewalCheck}.
 *
 * <p>RDs carry the same exposure and it was left uncovered: {@code detectAndLinkRdRenewal}
 * matches on the same narrow tolerances as the FD linker, net worth excludes {@code CLOSED} and
 * {@code MATURED_RENEWED} the same way, and so a rollover that fails to link double-counts
 * identically. An RD is arguably the more likely to drift, because its corpus is a computed
 * projection rather than a figure printed on an advice — so the "new principal ≈ old maturity
 * value" comparison the linker depends on starts from a softer number.
 *
 * <p>Compared against the matured RD's <em>projected corpus</em> rather than its monthly
 * instalment: what rolls into the successor is the accumulated pot, not the contribution.
 */
@Component
@RequiredArgsConstructor
public class UnlinkedRdRenewalCheck implements ReconciliationCheck {

    static final int LOOKAHEAD_DAYS = 45;
    static final double AMOUNT_TOLERANCE = 0.25;

    private final TrackingService trackingService;

    @Override public String id() { return "RD_UNLINKED_RENEWAL"; }
    @Override public String domain() { return "RD"; }
    @Override public String description() {
        return "A matured recurring deposit and a similar new one at the same bank are not "
             + "linked, so the same money may be counted twice in net worth";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<RdResponse> rds = trackingService.listRds(userId);
        List<ReconciliationIssue> issues = new ArrayList<>();

        for (RdResponse matured : rds) {
            if (!isUnresolvedMatured(matured)) continue;

            BigDecimal maturedCorpus = matured.getProjectedCorpus() != null
                ? matured.getProjectedCorpus() : matured.getCurrentValue();
            if (maturedCorpus == null || maturedCorpus.signum() <= 0) continue;
            if (matured.getMaturityDate() == null) continue;

            for (RdResponse successor : rds) {
                if (!isCandidateSuccessor(matured, successor)) continue;

                long gap = ChronoUnit.DAYS.between(matured.getMaturityDate(), successor.getStartDate());
                if (gap < 0 || gap > LOOKAHEAD_DAYS) continue;

                // An RD rollover usually reappears as a lump sum, so compare the successor's
                // whole projected corpus against the matured one.
                BigDecimal successorValue = successor.getProjectedCorpus() != null
                    ? successor.getProjectedCorpus() : successor.getTotalDeposited();
                if (successorValue == null || successorValue.signum() <= 0) continue;

                BigDecimal drift = successorValue.subtract(maturedCorpus).abs()
                    .divide(maturedCorpus, 6, RoundingMode.HALF_UP);
                if (drift.doubleValue() > AMOUNT_TOLERANCE) continue;

                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type(id()).severity("HIGH")
                    .referenceId(matured.getId())
                    .description(String.format(
                        "%s RD #%d matured at about %s on %s, and RD #%d started %d day(s) later "
                            + "with a projected corpus of %s. These look like the same money rolled "
                            + "over, but they are not linked — so both are counted and net worth may "
                            + "be overstated by about %s. Confirm whether #%d was renewed into #%d.",
                        matured.getBank(), matured.getId(), inr(maturedCorpus),
                        matured.getMaturityDate(), successor.getId(), gap,
                        inr(successorValue), inr(maturedCorpus),
                        matured.getId(), successor.getId()))
                    .build());
                break;
            }
        }
        return issues;
    }

    private boolean isUnresolvedMatured(RdResponse rd) {
        if (rd.getRenewedToId() != null) return false;
        if ("CLOSED".equalsIgnoreCase(rd.getStatus())
            || "MATURED_RENEWED".equalsIgnoreCase(rd.getStatus())) return false;
        if ("MATURED".equalsIgnoreCase(rd.getStatus())) return true;

        // Also covers an ACTIVE row whose maturity has simply passed — the daily sweep runs once
        // a day and the double count is live either way.
        return rd.getMaturityDate() != null && !LocalDate.now().isBefore(rd.getMaturityDate());
    }

    private boolean isCandidateSuccessor(RdResponse matured, RdResponse successor) {
        if (successor.getId().equals(matured.getId())) return false;
        if (successor.getRenewedFromId() != null) return false;
        if (successor.getStartDate() == null) return false;
        if ("CLOSED".equalsIgnoreCase(successor.getStatus())
            || "MATURED_RENEWED".equalsIgnoreCase(successor.getStatus())) return false;

        return UnlinkedRenewalCheck.normaliseBank(matured.getBank())
            .equals(UnlinkedRenewalCheck.normaliseBank(successor.getBank()));
    }

    private static String inr(BigDecimal v) {
        return "₹" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
