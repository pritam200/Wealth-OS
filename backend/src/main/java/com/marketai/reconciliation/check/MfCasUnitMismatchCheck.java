package com.marketai.reconciliation.check;

import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CAS/AMC statement's own stated closing unit balance for a folio, compared against what this
 * app's transaction ledger computes for the matching holding.
 *
 * <p>{@link CasBalanceSnapshot} is written only from a span-verified, sender-trust-gated
 * {@code closing_balances[]} entry (see {@code EmailLLMParserService}) — an independent,
 * document-stated figure, not something derived from the ledger it's being checked against.
 *
 * <p>Only the most recent snapshot per folio is compared: an older statement disagreeing with
 * today's ledger is expected (more transactions happened since), so comparing anything but the
 * latest would manufacture false mismatches.
 *
 * <p>Matches by folio first — the identifier the statement itself states — and falls back to the
 * holding's resolved AMFI scheme code only when no holding carries that folio at all. Per the
 * "report, never repair" rule this whole package follows: on a mismatch beyond tolerance, this
 * only flags the discrepancy for the user to look at. It never writes to the holding's quantity.
 * The app cannot know whether the statement or the ledger is right — a missed transaction, a
 * corporate action, a switch-in/out, or a genuine extraction error could each explain it — and
 * guessing which would silently fabricate or destroy a financial record.
 */
@Component
@RequiredArgsConstructor
public class MfCasUnitMismatchCheck implements ReconciliationCheck {

    /** Fractional-unit tolerance, not a rupee amount — this check is about a unit COUNT
     *  disagreeing, and MF unit counts round to 4 decimals throughout this app (see
     *  {@code Holding.quantity}), so anything beyond ordinary rounding noise is material. */
    static final BigDecimal TOLERANCE_UNITS = new BigDecimal("0.01");

    private final CasBalanceSnapshotRepository casBalanceSnapshotRepository;
    private final HoldingRepository holdingRepository;

    @Override public String id() { return "MF_CAS_UNIT_MISMATCH"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "A CAS/AMC statement's stated closing unit balance for a folio disagrees with "
             + "the unit count this app's own ledger computes for the matching holding";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();

        // findByUserIdOrderByAsOfDateDesc is ordered newest-first, so the first snapshot seen
        // for a given folio is its latest — later ones for the same folio are superseded and
        // deliberately skipped rather than compared.
        Map<String, CasBalanceSnapshot> latestByFolio = new LinkedHashMap<>();
        for (CasBalanceSnapshot snap : casBalanceSnapshotRepository.findByUserIdOrderByAsOfDateDesc(userId)) {
            latestByFolio.putIfAbsent(snap.getFolio(), snap);
        }

        for (CasBalanceSnapshot snap : latestByFolio.values()) {
            List<Holding> matches = holdingRepository.findByUserIdAndFolio(userId, snap.getFolio());
            if (matches.isEmpty() && snap.getSchemeCode() != null) {
                matches = holdingRepository.findByUserIdAndAmfiSchemeCode(userId, snap.getSchemeCode());
            }
            if (matches.isEmpty()) continue; // nothing in the ledger to compare against yet

            BigDecimal ledgerUnits = BigDecimal.ZERO;
            for (Holding h : matches) {
                if (h.getQuantity() != null) ledgerUnits = ledgerUnits.add(h.getQuantity());
            }

            BigDecimal diff = snap.getStatedUnits().subtract(ledgerUnits).abs();
            if (diff.compareTo(TOLERANCE_UNITS) <= 0) continue;

            Holding first = matches.get(0);
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("MEDIUM")
                .referenceId(first.getId())
                .description(String.format(
                    "Folio %s: the CAS statement dated %s states a closing balance of %s units, "
                        + "but this app's ledger computes %s units for the matching holding — a "
                        + "difference of %s units. Check for a missed transaction, a corporate "
                        + "action, or a switch-in/out before assuming either figure is wrong.",
                    snap.getFolio(), snap.getAsOfDate(), strip(snap.getStatedUnits()),
                    strip(ledgerUnits), strip(diff)))
                .build());
        }
        return issues;
    }

    private static String strip(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
