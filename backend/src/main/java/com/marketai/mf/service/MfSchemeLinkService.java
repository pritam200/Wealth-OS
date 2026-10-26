package com.marketai.mf.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.service.AmfiNavService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Links an MF {@link Holding} to its AMFI scheme code so that real NAV history and performance
 * can be attached to it.
 *
 * <p>The hard part is not finding a match, it is refusing a bad one. AMFI lists Direct and
 * Regular plans, and Growth and IDCW options, as separate scheme codes with materially
 * different NAVs and returns. A loose name match will happily pick the wrong one, and the
 * result would be one fund's performance displayed as another's — a fabricated figure with a
 * plausible-looking provenance. So {@link #resolve} only links on a confident match and
 * otherwise leaves the link null for the UI to report as "not linked".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfSchemeLinkService {

    /**
     * CONFIDENCE RULE (two gates, both must pass):
     *
     * <ol>
     *   <li><b>Token overlap ≥ 0.80</b> — Jaccard similarity (shared tokens ÷ total distinct
     *       tokens) between the holding name and the AMFI scheme name, after lowercasing and
     *       splitting on non-alphanumerics. 0.80 is set so that a holding name which simply
     *       omits the plan/option suffix cannot match: "SBI Blue Chip Fund" (4 tokens) against
     *       "SBI Blue Chip Fund Direct Plan Growth" (7) scores 4/7 = 0.57 and is rejected,
     *       because we cannot tell which of the four SBI Blue Chip scheme codes it is.
     *       An exact normalised-string match short-circuits to 1.0.</li>
     *   <li><b>No discriminator conflict</b> — if both names state a plan (Direct vs Regular)
     *       or an option (Growth vs IDCW/Dividend) and they disagree, the match is rejected
     *       outright regardless of score. This is a veto, not a penalty: "…Direct Plan Growth"
     *       vs "…Regular Plan Growth" differs by one token out of eight and would otherwise
     *       score well above the threshold.</li>
     * </ol>
     *
     * Deliberately biased toward "not linked": an unlinked holding is a visible gap, whereas a
     * wrong link is an invisible error.
     */
    static final double MIN_CONFIDENCE = 0.80;

    private static final Set<String> DIRECT_TOKENS = tokenSetOf("direct");
    private static final Set<String> REGULAR_TOKENS = tokenSetOf("regular");
    private static final Set<String> GROWTH_TOKENS = tokenSetOf("growth");
    private static final Set<String> INCOME_TOKENS = tokenSetOf("idcw dividend");

    private final AmfiNavService amfiNavService;
    private final HoldingRepository holdingRepository;

    /**
     * Resolve and persist the AMFI scheme code for a holding, if one can be matched
     * confidently. Cached on the row so the fuzzy match runs at most once per holding.
     *
     * @return the scheme code, or null when the holding isn't an MF or no confident match exists
     */
    @Transactional
    public String resolve(Holding holding) {
        if (holding == null) return null;
        String symbol = holding.getSymbol();
        if (symbol == null || !symbol.endsWith(".MF")) return holding.getAmfiSchemeCode();

        // An ISIN identifies exactly one plan and option, so it links without the name heuristics,
        // and corrects a code linked by name to the wrong plan ("Multi Cap Fund - Growth" taken
        // as the Direct plan when its ISIN is the Regular plan's).
        AmfiNavResult byIsin = amfiNavService.findByIsin(holding.getIsin());
        if (byIsin != null && byIsin.getSchemeCode().equals(holding.getAmfiSchemeCode())) return byIsin.getSchemeCode();
        if (byIsin == null && holding.getAmfiSchemeCode() != null) return holding.getAmfiSchemeCode();
        if (byIsin != null) {
            holding.setAmfiSchemeCode(byIsin.getSchemeCode());
            holdingRepository.save(holding);
            log.info("Linked MF holding '{}' -> AMFI scheme {} by ISIN {}", holding.getName(), byIsin.getSchemeCode(), holding.getIsin());
            return byIsin.getSchemeCode();
        }

        String name = holding.getName();
        if (name == null || name.trim().length() < 3) return null;

        AmfiNavResult candidate = bestCandidate(name);
        if (candidate == null) {
            log.debug("No AMFI candidate for MF holding '{}'", name);
            return null;
        }

        double confidence = confidence(name, candidate.getSchemeName());
        if (confidence < MIN_CONFIDENCE) {
            log.info("MF holding '{}' left unlinked: best AMFI candidate '{}' scored {} (< {})",
                    name, candidate.getSchemeName(), String.format("%.2f", confidence), MIN_CONFIDENCE);
            return null;
        }

        holding.setAmfiSchemeCode(candidate.getSchemeCode());
        holdingRepository.save(holding);
        log.info("Linked MF holding '{}' -> AMFI scheme {} ('{}') at confidence {}",
                name, candidate.getSchemeCode(), candidate.getSchemeName(),
                String.format("%.2f", confidence));
        return candidate.getSchemeCode();
    }

    /**
     * Resolves an AMFI scheme code from a bare fund name (no {@link Holding} involved), using the
     * exact same confidence rule as {@link #resolve} — a name that doesn't clear
     * {@link #MIN_CONFIDENCE} against its best AMFI candidate is left unresolved rather than
     * guessed.
     *
     * <p>Used by the LLM-based transaction extraction path ({@code EmailLLMParserService}) to
     * validate a scheme name the model returned before any transaction referencing it is booked
     * — an unresolved scheme name must route to review, never be imported under a fabricated
     * or approximate code.
     */
    public java.util.Optional<String> resolveSchemeCodeByFundName(String fundName) {
        if (fundName == null || fundName.trim().length() < 3) return java.util.Optional.empty();

        AmfiNavResult candidate = bestCandidate(fundName);
        if (candidate == null) return java.util.Optional.empty();

        double conf = confidence(fundName, candidate.getSchemeName());
        if (conf < MIN_CONFIDENCE) {
            log.debug("LLM-provided fund name '{}' left unresolved: best AMFI candidate '{}' scored {} (< {})",
                    fundName, candidate.getSchemeName(), String.format("%.2f", conf), MIN_CONFIDENCE);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(candidate.getSchemeCode());
    }

    /**
     * The AMFI scheme scoring highest against {@code name} under {@link #confidence}, or null when
     * two schemes tie for it — "SBI Aggressive Hybrid Fund Direct IDCW" names four IDCW schemes
     * that AMFI lists under one option label, and guessing among them is the invisible error the
     * rule exists to prevent. The first substring hit used to be taken instead, which picked a
     * plan at random once AMFI's names stopped carrying the plan and option.
     */
    private AmfiNavResult bestCandidate(String name) {
        List<AmfiNavResult> all = amfiNavService.all();
        if (all == null || all.isEmpty()) return amfiNavService.findByName(name);
        AmfiNavResult best = null;
        double bestScore = 0d;
        boolean tied = false;
        for (AmfiNavResult r : all) {
            double c = confidence(name, r.getSchemeName());
            if (c > bestScore) { best = r; bestScore = c; tied = false; }
            else if (c == bestScore && best != null && c > 0 && !best.getSchemeCode().equals(r.getSchemeCode())) tied = true;
        }
        return tied ? null : best;
    }

    /** Attempt to link every MF holding that has no scheme code yet. @return number newly linked */
    @Transactional
    public int linkUnlinkedHoldings() {
        List<Holding> unlinked = holdingRepository.findUnlinkedMfHoldings();
        int linked = 0;
        for (Holding h : unlinked) {
            try {
                if (resolve(h) != null) linked++;
            } catch (Exception e) {
                log.warn("Scheme link failed for holding {}: {}", h.getId(), e.getMessage());
            }
        }
        if (!unlinked.isEmpty()) {
            log.info("MF scheme linking: {} of {} unlinked holdings resolved", linked, unlinked.size());
        }
        for (Holding h : holdingRepository.findLinkedMfHoldingsWithIsin()) {
            String before = h.getAmfiSchemeCode();
            try {
                String after = resolve(h);
                if (after != null && !after.equals(before)) {
                    log.info("MF holding {} relinked from scheme {} to {} by its ISIN", h.getId(), before, after);
                    linked++;
                }
            } catch (Exception e) {
                log.warn("Scheme check failed for holding {}: {}", h.getId(), e.getMessage());
            }
        }
        return linked;
    }

    /**
     * Match confidence in [0,1]. Returns 0 on a plan/option conflict (see {@link #MIN_CONFIDENCE}).
     * Package-visible so the rule is unit-testable without a Spring context.
     */
    double confidence(String holdingName, String schemeName) {
        if (holdingName == null || schemeName == null) return 0d;

        if (normalize(holdingName).equals(normalize(schemeName))) return 1d;

        Set<String> a = tokens(holdingName);
        Set<String> b = tokens(schemeName);
        if (a.isEmpty() || b.isEmpty()) return 0d;

        // Veto gate: an explicit disagreement on plan or option means these are different
        // scheme codes, however similar the rest of the name reads.
        if (conflicts(a, b, DIRECT_TOKENS, REGULAR_TOKENS)) return 0d;
        if (conflicts(a, b, GROWTH_TOKENS, INCOME_TOKENS)) return 0d;

        int shared = 0;
        for (String t : a) if (b.contains(t)) shared++;
        int union = a.size() + b.size() - shared;
        return union == 0 ? 0d : (double) shared / union;
    }

    /**
     * True when one name declares {@code left} and the other declares {@code right}. A name
     * that declares both (or neither) is treated as making no claim, so it cannot veto.
     */
    private boolean conflicts(Set<String> a, Set<String> b, Set<String> left, Set<String> right) {
        Boolean aSide = side(a, left, right);
        Boolean bSide = side(b, left, right);
        return aSide != null && bSide != null && !aSide.equals(bSide);
    }

    /** TRUE = left family, FALSE = right family, null = unstated or ambiguous. */
    private Boolean side(Set<String> tokens, Set<String> left, Set<String> right) {
        boolean hasLeft = intersects(tokens, left);
        boolean hasRight = intersects(tokens, right);
        if (hasLeft == hasRight) return null; // neither, or contradictory within one name
        return hasLeft;
    }

    private boolean intersects(Set<String> tokens, Set<String> markers) {
        for (String m : markers) if (tokens.contains(m)) return true;
        return false;
    }

    // "(formerly HDFC Large Cap Fund)", "(erstwhile Bluechip Fund)", "(Non -Demat)": history and
    // holding form, not part of which scheme it is.
    private static final java.util.regex.Pattern NAME_NOTES =
        java.util.regex.Pattern.compile("\\((?:formerly|erstwhile|non\\s*-?\\s*demat)[^)]*\\)");
    // Registrars shorten the plan and option, and AMFI calls some funds' growth option "Cumulative".
    private static final java.util.Map<String, String> SYNONYMS = java.util.Map.of(
        "dir", "direct", "reg", "regular", "g", "growth", "cumulative", "growth");

    private Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        String cleaned = NAME_NOTES.matcher(s.toLowerCase(Locale.ENGLISH)).replaceAll(" ");
        for (String t : cleaned.split("[^a-z0-9]+")) {
            // "Direct Plan - Growth Option" and "Direct Growth" say the same.
            if (t.isEmpty() || t.equals("plan") || t.equals("option")) continue;
            out.add(SYNONYMS.getOrDefault(t, t));
        }
        return out;
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> tokenSetOf(String spaceSeparated) {
        return new LinkedHashSet<>(Arrays.asList(spaceSeparated.split(" ")));
    }
}
