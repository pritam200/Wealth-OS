package com.marketai.reconciliation.check;

import com.marketai.dataplatform.domain.AccountType;
import com.marketai.dataplatform.domain.AssetClass;
import com.marketai.dataplatform.domain.BasisType;
import com.marketai.dataplatform.domain.FinancialAccount;
import com.marketai.dataplatform.domain.FinancialAsset;
import com.marketai.dataplatform.domain.HoldingSnapshot;
import com.marketai.dataplatform.repo.FinancialAccountRepository;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.dataplatform.repo.HoldingSnapshotRepository;
import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A CAS/AMC statement's own stated closing unit balance for a scheme in a folio, compared against
 * what this app's transaction ledger gives for the matching holding on the statement's date.
 *
 * <p>Two sources state a closing balance, and both are read: {@link CasBalanceSnapshot}, written
 * from a span-verified, sender-trust-gated {@code closing_balances[]} entry in an email (see
 * {@code EmailLLMParserService}), and the institution-reported holdings a CAS PDF import stores
 * (see {@code CasImportService}). Each is an independent, document-stated figure, not something
 * derived from the ledger it's being checked against.
 *
 * <p>A folio can hold several schemes, so a statement line is one scheme in one folio — keyed by
 * its ISIN, else its AMFI scheme code. Only the most recent line per scheme is compared: an older
 * statement disagreeing with today's ledger is expected (more transactions happened since), so
 * comparing anything but the latest would manufacture false mismatches. For the same reason the
 * ledger side is the position its trades give on the statement date.
 *
 * <p>Matched by folio and ISIN, then folio and scheme code; by folio alone only when the folio has
 * a single holding, and by scheme code alone only when no holding carries the folio at all. Per
 * the "report, never repair" rule this whole package follows: on a mismatch beyond tolerance,
 * this only flags the discrepancy for the user to look at. It never writes to the holding's
 * quantity. The app cannot know whether the statement or the ledger is right — a missed
 * transaction, a corporate action, a switch-in/out, or a genuine extraction error could each
 * explain it — and guessing which would silently fabricate or destroy a financial record.
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
    private final TransactionRepository transactionRepository;
    private final HoldingSnapshotRepository holdingSnapshotRepository;
    private final FinancialAccountRepository accountRepository;
    private final FinancialAssetRepository assetRepository;

    @Override public String id() { return "MF_CAS_UNIT_MISMATCH"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "A CAS/AMC statement's stated closing unit balance for a scheme in a folio disagrees with "
             + "the unit count this app's own ledger gives for the matching holding on that date";
    }

    /** One scheme's closing balance in one folio, as a statement states it. */
    record Stated(String folio, String isin, String schemeCode, String name, LocalDate asOf, BigDecimal units) {
        String key() {
            return normFolio(folio) + "|" + (isin != null ? isin : schemeCode != null ? "S:" + schemeCode : "?");
        }
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        // Newest first, so the first line seen for a scheme in a folio is its latest — later ones
        // are superseded and deliberately skipped rather than compared.
        List<Stated> all = new ArrayList<>();
        for (CasBalanceSnapshot s : casBalanceSnapshotRepository.findByUserIdOrderByAsOfDateDesc(userId)) {
            all.add(new Stated(s.getFolio(), clean(s.getIsin()), s.getSchemeCode(), null, s.getAsOfDate(), s.getStatedUnits()));
        }
        all.addAll(fromCasImports(userId));
        all.sort(Comparator.comparing(Stated::asOf, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Stated> latest = new LinkedHashMap<>();
        for (Stated s : all) if (s.folio() != null && s.asOf() != null && s.units() != null) latest.putIfAbsent(s.key(), s);

        List<ReconciliationIssue> issues = new ArrayList<>();
        Map<String, List<Holding>> byFolio = new HashMap<>();
        for (Stated s : latest.values()) {
            List<Holding> inFolio = byFolio.computeIfAbsent(normFolio(s.folio()), f -> holdingRepository.findByUserIdAndFolio(userId, s.folio()));
            List<Holding> matches = match(s, inFolio);
            if (matches == null && inFolio.isEmpty() && s.schemeCode() != null) {
                matches = holdingRepository.findByUserIdAndAmfiSchemeCode(userId, s.schemeCode());
            }
            if (matches == null || matches.isEmpty()) continue; // nothing in the ledger to compare against yet

            // A fund's units are allotted on the transaction date, so the position on the statement date
            // is the one to compare; a holding with no trades on record is compared at its quantity.
            BigDecimal ledgerUnits = BigDecimal.ZERO;
            for (Holding h : matches) {
                List<Transaction> own = transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId());
                ledgerUnits = ledgerUnits.add(StatementPositions.acceptable(h, own, s.asOf()).get(0));
            }
            if (StatementPositions.same(s.units(), ledgerUnits, TOLERANCE_UNITS)) continue;

            Holding first = matches.get(0);
            BigDecimal diff = s.units().subtract(ledgerUnits).abs();
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("MEDIUM")
                .referenceId(first.getId())
                .description(String.format(
                    "Folio %s, %s: the CAS statement dated %s states a closing balance of %s units, "
                        + "but this app's ledger gives %s units for the matching holding on that date — a "
                        + "difference of %s units. Check for a missed transaction, a corporate "
                        + "action, or a switch-in/out before assuming either figure is wrong.",
                    s.folio(), first.getName() != null ? first.getName() : s.name() != null ? s.name() : first.getSymbol(),
                    s.asOf(), StatementPositions.units(s.units()), StatementPositions.units(ledgerUnits), StatementPositions.units(diff)))
                .build());
        }
        return issues;
    }

    /**
     * The holdings in a folio a statement line is about, or null when the line can't be told
     * apart from the folio's other schemes.
     */
    static List<Holding> match(Stated s, List<Holding> inFolio) {
        if (inFolio.isEmpty()) return null;
        if (s.isin() != null) {
            List<Holding> m = inFolio.stream().filter(h -> s.isin().equalsIgnoreCase(clean(h.getIsin()))).toList();
            if (!m.isEmpty()) return m;
        }
        if (s.schemeCode() != null) {
            List<Holding> m = inFolio.stream().filter(h -> s.schemeCode().equals(h.getAmfiSchemeCode())).toList();
            if (!m.isEmpty()) return m;
        }
        // A folio with one scheme in it: that's the one, whatever identifiers either side lacks —
        // unless both carry an ISIN and they differ, which makes it another scheme.
        if (inFolio.size() == 1) {
            Holding h = inFolio.get(0);
            boolean otherScheme = (s.isin() != null && clean(h.getIsin()) != null)
                || (s.schemeCode() != null && h.getAmfiSchemeCode() != null);
            return otherScheme ? null : inFolio;
        }
        return null;
    }

    /** Closing balances a CAS PDF import stored, one per scheme in each folio. */
    private List<Stated> fromCasImports(Long userId) {
        Map<Long, FinancialAccount> folios = accountRepository.findByOwnerUserId(userId).stream()
            .filter(a -> a.getAccountType() == AccountType.MF_FOLIO)
            .collect(Collectors.toMap(FinancialAccount::getId, Function.identity()));
        if (folios.isEmpty()) return List.of();
        List<HoldingSnapshot> reported = holdingSnapshotRepository.findByAccountIdIn(folios.keySet()).stream()
            .filter(s -> s.getBasis() == BasisType.INSTITUTION_REPORTED).toList();
        if (reported.isEmpty()) return List.of();
        Map<Long, FinancialAsset> assets = assetRepository.findAllById(reported.stream().map(HoldingSnapshot::getAssetId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAsset::getId, Function.identity()));
        List<Stated> out = new ArrayList<>();
        for (HoldingSnapshot s : reported) {
            FinancialAsset a = assets.get(s.getAssetId());
            String folio = folioOf(folios.get(s.getAccountId()));
            if (a == null || a.getAssetClass() != AssetClass.MUTUAL_FUND || folio == null) continue;
            out.add(new Stated(folio, clean(a.getIsin()), a.getAmfiCode(), a.getName(), s.getAsOfDate(), s.getQuantity()));
        }
        return out;
    }

    /** The folio a folio account was filed under: its key is "INSTITUTION:FOLIO". */
    static String folioOf(FinancialAccount a) {
        if (a == null || a.getAccountKey() == null) return null;
        int i = a.getAccountKey().indexOf(':');
        String f = i < 0 ? null : a.getAccountKey().substring(i + 1);
        return f == null || f.isBlank() || "DEFAULT".equals(f) ? null : f;
    }

    /** "12345678 / 90" and "12345678/90" are one folio. */
    static String normFolio(String f) {
        return f == null ? "" : f.replaceAll("\\s+", "").toUpperCase();
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
