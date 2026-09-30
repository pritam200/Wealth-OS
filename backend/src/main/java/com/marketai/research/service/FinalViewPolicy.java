package com.marketai.research.service;

import com.marketai.research.model.DevilsAdvocate;
import com.marketai.research.model.FinalView;
import com.marketai.research.model.QuantAssessment;
import com.marketai.research.model.ResearchReport;

import java.util.Set;

/**
 * How the quantitative assessment and the research are brought together — a fixed gate, not a
 * weighted score. A directional call needs both a validated quantitative edge and research that
 * agrees; research alone never creates a call, and disagreement is shown as disagreement.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>DATA_GATE — stale or insufficient price data: INSUFFICIENT_DATA.</li>
 *   <li>QUANT_ONLY — no research: a validated BUY/SELL stands, anything else is NO_ACTIONABLE_SIGNAL.</li>
 *   <li>RESEARCH_FLAG — research says CONFLICTING_EVIDENCE, RESEARCH_REQUIRED or INSUFFICIENT_DATA: that.</li>
 *   <li>CONFIRMED — validated call and research agree: the call, unless the review rates thesis risk HIGH (CONFLICTING_EVIDENCE).</li>
 *   <li>OPPOSED — validated call and research point opposite ways: CONFLICTING_EVIDENCE.</li>
 *   <li>NOT_CONFIRMED — validated call, research neutral: NO_ACTIONABLE_SIGNAL.</li>
 *   <li>NO_EDGE — no validated call: NO_ACTIONABLE_SIGNAL whatever the research leans.</li>
 * </ol>
 */
public final class FinalViewPolicy {

    private FinalViewPolicy() {}

    static final Set<String> FLAGS = Set.of("CONFLICTING_EVIDENCE", "RESEARCH_REQUIRED", "INSUFFICIENT_DATA");

    public static FinalView decide(boolean dataUsable, QuantAssessment q, ResearchReport report, DevilsAdvocate review) {
        String quant = q != null ? q.rating() : null;
        boolean validated = q != null && q.validated() && ("BUY".equals(quant) || "SELL".equals(quant));
        String lean = report != null ? report.getActionability() : null;

        if (!dataUsable) {
            return new FinalView("INSUFFICIENT_DATA",
                    "Price data is " + ("STALE_DATA".equals(q != null ? q.seriesStatus() : null) ? "stale" : "insufficient")
                    + " — no view is given until it is current.", "DATA_GATE", quant, lean);
        }
        if (report == null) {
            return validated
                    ? new FinalView(quant, "AI research unavailable — quantitative view only (validated " + quant + ").", "QUANT_ONLY", quant, null)
                    : new FinalView("NO_ACTIONABLE_SIGNAL", "AI research unavailable — quantitative view only: no validated edge.", "QUANT_ONLY", quant, null);
        }
        if (FLAGS.contains(lean)) {
            return new FinalView(lean, switch (lean) {
                case "CONFLICTING_EVIDENCE" -> "Research found material evidence pointing both ways.";
                case "RESEARCH_REQUIRED" -> "Research found a pending event or missing information that decides the case.";
                default -> "Research found the available data too thin for a view.";
            }, "RESEARCH_FLAG", quant, lean);
        }
        if (validated) {
            String opposite = "BUY".equals(quant) ? "SELL" : "BUY";
            if (quant.equals(lean)) {
                if (review != null && "HIGH".equals(review.getThesisRisk())) {
                    return new FinalView("CONFLICTING_EVIDENCE", "The validated " + quant + " and the research agree, but the devil's-advocate review rates the risk to that thesis HIGH.",
                            "CONFIRMED", quant, lean);
                }
                return new FinalView(quant, "Validated quantitative " + quant + " confirmed by research.", "CONFIRMED", quant, lean);
            }
            if (opposite.equals(lean)) {
                return new FinalView("CONFLICTING_EVIDENCE", "The validated quantitative call is " + quant + " but research leans " + lean + ".", "OPPOSED", quant, lean);
            }
            return new FinalView("NO_ACTIONABLE_SIGNAL", "Validated quantitative " + quant + " not confirmed by research (research: " + label(lean) + ").", "NOT_CONFIRMED", quant, lean);
        }
        String why = "NO_ACTIONABLE_SIGNAL".equals(lean) || lean == null
                ? "Neither the quantitative model nor the research finds an actionable signal."
                : "Research leans " + lean + ", but there is no validated quantitative edge — research alone does not make a call.";
        return new FinalView("NO_ACTIONABLE_SIGNAL", why, "NO_EDGE", quant, lean);
    }

    private static String label(String s) {
        return s == null ? "none" : s.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }
}
