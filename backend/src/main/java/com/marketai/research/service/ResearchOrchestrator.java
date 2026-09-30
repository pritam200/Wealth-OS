package com.marketai.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.llm.LlmTask;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.prompt.PromptLibrary;
import com.marketai.ai.prompt.PromptTemplate;
import com.marketai.research.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one research engine. Every screen that shows research — stock page, Market Forecast,
 * AI Advisor, recommendations, Today's Actions — gets it from here or from what this stored.
 *
 * <p>Flow: verified context (code) → cache lookup → web research (Gemini routes only) →
 * analyst pass → devil's-advocate pass → validation and number guard → final-view gate →
 * store. The model interprets; it never supplies a number, and nothing it writes changes the
 * deterministic analysis. When it fails, the deterministic view stands on its own and the
 * last research, if any, is shown with its date.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchOrchestrator {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LlmTask TASK = LlmTask.FINANCIAL_ANALYSIS;
    static final int MAX_WEB_EVIDENCE = 12;
    static final String PROMPT_VERSION = PromptLibrary.RESEARCH_ANALYST.tag() + "+" + PromptLibrary.RESEARCH_DEVILS_ADVOCATE.tag()
            + "+" + PromptLibrary.RESEARCH_WEB.tag();

    public enum Mode {
        /** Stored research only — never calls a model. */
        CACHED,
        /** Stored research for exactly these inputs, else run. */
        AUTO,
        /** Always run (new information, or the user asked). */
        REFRESH
    }

    private final ResearchContextBuilder builder;
    private final LlmService llm;
    private final LlmJsonParser json;
    private final ResearchCache cache;
    private final ObjectMapper mapper;

    public ResearchResult stock(String symbol, String name, Long userId, Mode mode) {
        return run(builder.forStock(symbol, name, userId), mode);
    }

    public ResearchResult index(String symbol, String name, Mode mode) {
        return run(builder.forIndex(symbol, name), mode);
    }

    public ResearchResult fund(String symbol, Long userId, Mode mode) {
        return run(builder.forFund(symbol, userId), mode);
    }

    public ResearchResult run(ResearchContext ctx, Mode mode) {
        long started = System.currentTimeMillis();
        String[] route = llm.describe(TASK).split(":", 2);
        String provider = route[0], model = route.length > 1 ? route[1] : "none";
        ResearchCache.Key key = new ResearchCache.Key(ctx.subjectType(), ctx.symbol(), ctx.userId(), ctx.marketDate(),
                ctx.snapshotHash(), PROMPT_VERSION, provider, model);

        if (mode != Mode.REFRESH) {
            Optional<ResearchResult> hit = cache.find(key);
            if (hit.isPresent()) return hit.get().toBuilder().fromCache(true).build();
        }
        ResearchResult base = base(ctx);
        if (mode == Mode.CACHED) {
            return withPrevious(base, ctx, "NOT_RUN", "No research yet for the current data.");
        }
        if (!llm.isAvailable(TASK)) {
            return withPrevious(base, ctx, "DISABLED", "No model is configured for " + TASK.label() + " in LLM Configuration.");
        }

        // Web research is gathered before the analyst pass so the analyst can cite it.
        List<Evidence> evidence = new ArrayList<>(ctx.evidence());
        List<SourceStatus> sources = new ArrayList<>(ctx.sources());
        List<String> queries = new ArrayList<>();
        String webVersion = null;
        try {
            Optional<LlmCompletion> web = llm.webResearch(TASK, PromptLibrary.RESEARCH_WEB, webSubject(ctx));
            if (web.isEmpty()) {
                sources.add(SourceStatus.notSupported("Web research (search grounding)",
                        "The model routed to " + TASK.label() + " (" + provider + ":" + model + ") cannot search the web; only Gemini routes can."));
            } else {
                webVersion = PromptLibrary.RESEARCH_WEB.tag();
                List<Evidence> found = webEvidence(web.get(), evidence.size());
                evidence.addAll(found);
                if (web.get().getGrounding() != null && web.get().getGrounding().queries() != null) queries.addAll(web.get().getGrounding().queries());
                sources.add(found.isEmpty()
                        ? new SourceStatus("Web research (search grounding)", "OK", 0, "No grounded findings returned")
                        : SourceStatus.ok("Web research (search grounding)", found.size()));
            }
        } catch (LlmUnavailableException e) {
            sources.add(SourceStatus.unavailable("Web research (search grounding)", e.getMessage()));
        }

        String context = render(ctx, evidence, sources);
        ResearchOutputValidator validator = new ResearchOutputValidator(ctx.facts(), evidence);
        LlmCompletion analystCall;
        ResearchReport report;
        try {
            analystCall = llm.complete(TASK, PromptLibrary.RESEARCH_ANALYST, context);
            try {
                report = validator.report(json.parse(analystCall.getText()).orElse(null));
            } catch (ResearchOutputValidator.Invalid first) {
                log.info("Research reply for {} failed validation ({}); retrying once", ctx.symbol(), first.getMessage());
                analystCall = llm.complete(TASK, PromptLibrary.RESEARCH_ANALYST, context
                        + "\n\nYour previous reply was rejected: " + first.getMessage() + ". Reply with the JSON object only, in the shape given.");
                report = validator.report(json.parse(analystCall.getText()).orElse(null));
            }
        } catch (LlmUnavailableException e) {
            return withPrevious(base, ctx, "UNAVAILABLE", "AI research unavailable: " + e.getMessage());
        } catch (ResearchOutputValidator.Invalid e) {
            return withPrevious(base, ctx, "UNAVAILABLE", "AI research unavailable: the model's reply failed validation twice (" + e.getMessage() + ").");
        }

        DevilsAdvocate review = null;
        String status = "OK", statusReason = null, reviewVersion = null;
        try {
            LlmCompletion r = llm.complete(TASK, PromptLibrary.RESEARCH_DEVILS_ADVOCATE,
                    context + "\n\nANALYST RESEARCH (to challenge):\n" + mapper.writeValueAsString(report));
            review = validator.devilsAdvocate(json.parse(r.getText()).orElse(null));
            reviewVersion = PromptLibrary.RESEARCH_DEVILS_ADVOCATE.tag();
        } catch (LlmUnavailableException e) {
            status = "PARTIAL";
            statusReason = "Devil's-advocate review unavailable: " + e.getMessage();
        } catch (ResearchOutputValidator.Invalid e) {
            status = "PARTIAL";
            statusReason = "Devil's-advocate review failed validation: " + e.getMessage();
        } catch (Exception e) {
            status = "PARTIAL";
            statusReason = "Devil's-advocate review failed: " + e.getClass().getSimpleName();
        }

        NumberGuard guard = new NumberGuard(ctx.facts(), evidence);
        report = guard(report, guard);
        review = guard(review, guard);
        List<String> notes = new ArrayList<>(validator.notes());
        if (!guard.removed().isEmpty()) notes.add(guard.removed().size() + " figure(s) not found in the verified data were removed from the model's text.");
        if (!validator.droppedCitations().isEmpty()) notes.add("Citations to ids not in the context were dropped: " + String.join(", ", validator.droppedCitations()));

        ResearchResult out = base.toBuilder()
                .status(status).statusReason(statusReason)
                .researchTimestamp(LocalDateTime.now(IST).withNano(0))
                .provider(analystCall.getProvider()).model(analystCall.getModel()).fallbackUsed(analystCall.isFallbackUsed())
                .analystPromptVersion(analystCall.getPromptVersion()).reviewPromptVersion(reviewVersion).webPromptVersion(webVersion)
                .evidence(evidence).sources(sources)
                .report(report).devilsAdvocate(review)
                .finalView(FinalViewPolicy.decide(ctx.dataUsable(), ctx.quant(), report, review))
                .guard(new GuardReport(guard.removed(), validator.droppedCitations(), notes))
                .webSearchQueries(queries)
                .latencyMs(System.currentTimeMillis() - started)
                .build();
        // Stored under the route actually used, so a fallback answer is not served as the primary model's.
        cache.save(out, PROMPT_VERSION);
        return out;
    }

    /** The deterministic part, which stands on its own whether or not research runs. */
    private static ResearchResult base(ResearchContext ctx) {
        return ResearchResult.builder()
                .subjectType(ctx.subjectType()).symbol(ctx.symbol()).displayName(ctx.displayName()).userScope(ctx.userId())
                .marketDate(ctx.marketDate()).dataSnapshotHash(ctx.snapshotHash())
                .quant(ctx.quant()).facts(ctx.facts()).evidence(ctx.evidence()).sources(ctx.sources()).missingData(ctx.missing())
                .finalView(FinalViewPolicy.decide(ctx.dataUsable(), ctx.quant(), null, null))
                .webSearchQueries(List.of())
                .build();
    }

    /**
     * No current research: the deterministic view, plus the most recent research as
     * {@code previous}, labelled with its date. Research made on other data does not feed the
     * final view. Research on the same data (a different model or prompt made it) is shown as
     * the current research, marked as coming from that model.
     */
    private ResearchResult withPrevious(ResearchResult base, ResearchContext ctx, String status, String reason) {
        Optional<ResearchResult> last = cache.latest(ctx.subjectType(), ctx.symbol(), ctx.userId());
        if (last.isEmpty()) return base.toBuilder().status(status).statusReason(reason).build();
        ResearchResult p = last.get();
        if (Objects.equals(p.getDataSnapshotHash(), ctx.snapshotHash())) {
            return p.toBuilder().fromCache(true).statusReason(reason + " Showing research made on this same data by "
                    + p.getProvider() + ":" + p.getModel() + ".").build();
        }
        String when = p.getResearchTimestamp() != null ? p.getResearchTimestamp().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)) : "an earlier run";
        return base.toBuilder().status(status).statusReason(reason)
                .stale(true)
                .staleReason("Last research: " + when + " IST, on market data to " + p.getMarketDate()
                        + ". The data has changed since; that research does not reflect it.")
                .previous(p.toBuilder().previous(null).build())
                .build();
    }

    /* ───────────────────────── prompt rendering ───────────────────────── */

    /** The verified context of a result, as the model sees it. */
    public static String render(ResearchResult r) {
        ResearchContext ctx = new ResearchContext(r.getSubjectType(), r.getSymbol(), r.getDisplayName(), r.getMarketDate(),
                r.getFacts(), r.getEvidence(), r.getSources(), r.getMissingData(), r.getQuant(), r.getUserScope(), r.getDataSnapshotHash());
        return render(ctx, r.getEvidence(), r.getSources());
    }

    static String render(ResearchContext ctx, List<Evidence> evidence, List<SourceStatus> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("SUBJECT: ").append(ctx.displayName()).append(" (").append(ctx.symbol()).append(", ").append(ctx.subjectType()).append(")\n");
        sb.append("LATEST COMPLETED SESSION IN THE DATA: ").append(ctx.marketDate()).append('\n');
        sb.append("TODAY: ").append(LocalDate.now(IST)).append("\n\n");
        QuantAssessment q = ctx.quant();
        if (q != null) {
            sb.append("QUANTITATIVE ASSESSMENT (deterministic, tested — interpret, do not change):\n");
            sb.append("- Rating: ").append(q.rating()).append(q.validated() ? " (validated)" : " (not validated)").append('\n');
            sb.append("- ").append(q.summary()).append('\n');
            if (q.signalSummary() != null) sb.append("- Signal validation: ").append(q.signalSummary()).append('\n');
            if (q.trend() != null) sb.append("- Trend (descriptive only): ").append(q.trend()).append(q.trendSummary() != null ? ", " + q.trendSummary() : "").append('\n');
            for (String f : q.forecastSummary()) sb.append("- Forecast ").append(f).append('\n');
            if (q.calibrationSummary() != null) sb.append("- Calibration: ").append(q.calibrationSummary()).append('\n');
            sb.append('\n');
        }
        sb.append("VERIFIED FACTS (cite by id):\n");
        String cat = null;
        for (Fact f : ctx.facts()) {
            if (!Objects.equals(cat, f.category())) { cat = f.category(); sb.append("# ").append(cat).append('\n'); }
            sb.append('[').append(f.id()).append("] ").append(f.label()).append(": ");
            if (f.available()) {
                sb.append(f.value());
                if (f.asOf() != null) sb.append(" (as of ").append(f.asOf()).append(')');
                sb.append(" — ").append(f.basis()).append(", ").append(f.source());
            } else {
                sb.append("UNAVAILABLE — ").append(f.source());
            }
            sb.append('\n');
        }
        sb.append("\nEVIDENCE (cite by id; newest first within each source):\n");
        if (evidence.isEmpty()) sb.append("(none retrieved)\n");
        for (Evidence e : evidence) {
            sb.append('[').append(e.id()).append("] ").append(e.kind()).append(", ").append(e.tier()).append(", ").append(e.source());
            sb.append(", ").append(e.publishedAt() != null ? e.publishedAt().toLocalDate() : "undated").append(": ").append(e.title());
            if (e.detail() != null && !e.detail().isBlank()) sb.append(" — ").append(e.detail());
            sb.append('\n');
        }
        sb.append("\nSOURCES CHECKED:\n");
        for (SourceStatus s : sources) {
            sb.append("- ").append(s.source()).append(": ").append(s.status());
            if ("OK".equals(s.status())) sb.append(" (").append(s.items()).append(" item(s))");
            if (s.detail() != null) sb.append(" — ").append(s.detail());
            sb.append('\n');
        }
        sb.append("\nA source marked UNAVAILABLE was not checked: do not read its absence as 'no news' or 'no filings'.");
        return sb.toString();
    }

    static String webSubject(ResearchContext ctx) {
        String date = "Today is " + LocalDate.now(IST) + ".";
        return switch (ctx.subjectType()) {
            case "INDEX" -> date + " Subject: the Indian equity market (" + ctx.displayName() + "). Cover global and US/Asian markets, "
                    + "crude oil, USD/INR, bond yields, RBI policy, inflation, FII/DII flows, India VIX, scheduled events, earnings season and geopolitics.";
            case "MUTUAL_FUND" -> date + " Subject: the Indian mutual fund scheme \"" + ctx.displayName() + "\". Cover fund manager changes, "
                    + "mandate or category changes, SEBI or AMFI actions, expense ratio changes and material news about the fund house.";
            default -> date + " Subject: " + ctx.displayName() + " (NSE: " + ctx.symbol().replace(".NS", "") + "), a listed Indian company.";
        };
    }

    private static final Pattern WEB_DATE = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2})]");

    /** One evidence item per grounded statement, linked to the page that supports it. */
    static List<Evidence> webEvidence(LlmCompletion c, int offset) {
        List<Evidence> out = new ArrayList<>();
        LlmCompletion.Grounding g = c.getGrounding();
        if (g == null || g.supports() == null || g.sources() == null) return out;
        LocalDateTime now = LocalDateTime.now(IST).withNano(0);
        Set<String> seen = new HashSet<>();
        for (LlmCompletion.Support s : g.supports()) {
            if (out.size() >= MAX_WEB_EVIDENCE) break;
            if (s.text() == null || s.sourceIndexes() == null || s.sourceIndexes().isEmpty()) continue;
            int idx = s.sourceIndexes().get(0);
            if (idx < 0 || idx >= g.sources().size()) continue;
            LlmCompletion.Source src = g.sources().get(idx);
            String text = s.text().replaceFirst("^\\s*[-*•]\\s*", "").trim();
            if (text.isEmpty() || !seen.add(text)) continue;
            LocalDateTime published = null;
            Matcher m = WEB_DATE.matcher(text);
            if (m.find()) {
                try { published = LocalDate.parse(m.group(1)).atStartOfDay(); } catch (Exception ignored) { }
                text = text.substring(0, m.start()) + text.substring(m.end());
                text = text.trim();
            }
            out.add(new Evidence("E" + (offset + out.size() + 1), "WEB", EvidenceRetriever.tierOf(src.title(), src.uri()), src.title(),
                    text, published, src.uri(), now, published == null ? "LOW" : "MEDIUM",
                    "Found by web search; the statement is the model's summary of the linked page"));
        }
        return out;
    }

    /* ───────────────────────── number guard ───────────────────────── */

    private static Claim g(Claim c, NumberGuard ng) {
        return c == null ? null : new Claim(ng.clean(c.text()), c.evidence(), c.basis());
    }

    private static List<Claim> g(List<Claim> cs, NumberGuard ng) {
        return cs == null ? List.of() : cs.stream().map(c -> g(c, ng)).toList();
    }

    static ResearchReport guard(ResearchReport r, NumberGuard ng) {
        if (r == null) return null;
        return ResearchReport.builder()
                .executiveSummary(g(r.getExecutiveSummary(), ng)).fundamentalAssessment(g(r.getFundamentalAssessment(), ng))
                .technicalAssessment(g(r.getTechnicalAssessment(), ng)).marketContext(g(r.getMarketContext(), ng))
                .sectorContext(g(r.getSectorContext(), ng)).newsAssessment(g(r.getNewsAssessment(), ng))
                .valuationAssessment(g(r.getValuationAssessment(), ng)).portfolioImpact(g(r.getPortfolioImpact(), ng))
                .forecastInterpretation(g(r.getForecastInterpretation(), ng))
                .bullCase(g(r.getBullCase(), ng)).baseCase(g(r.getBaseCase(), ng)).bearCase(g(r.getBearCase(), ng))
                .crossChecks(r.getCrossChecks() == null ? List.of() : r.getCrossChecks().stream()
                        .map(x -> new CrossCheck(x.question(), x.label(), x.answer(), g(x.explanation(), ng))).toList())
                .contradictingEvidence(g(r.getContradictingEvidence(), ng)).keyRisks(g(r.getKeyRisks(), ng)).catalysts(g(r.getCatalysts(), ng))
                .missingInformation(r.getMissingInformation() == null ? List.of() : r.getMissingInformation().stream().map(ng::clean).toList())
                .evidenceQuality(r.getEvidenceQuality())
                .researchConclusion(g(r.getResearchConclusion(), ng))
                .actionability(r.getActionability())
                .sources(r.getSources())
                .build();
    }

    static DevilsAdvocate guard(DevilsAdvocate d, NumberGuard ng) {
        if (d == null) return null;
        return DevilsAdvocate.builder()
                .contradictoryEvidence(g(d.getContradictoryEvidence(), ng)).overlookedRisks(g(d.getOverlookedRisks(), ng))
                .dataQualityProblems(g(d.getDataQualityProblems(), ng)).upcomingCatalysts(g(d.getUpcomingCatalysts(), ng))
                .technicalSignalFailure(g(d.getTechnicalSignalFailure(), ng)).fundamentalThesisFailure(g(d.getFundamentalThesisFailure(), ng))
                .forecastRangeReliability(g(d.getForecastRangeReliability(), ng))
                .thesisRisk(d.getThesisRisk()).verdict(g(d.getVerdict(), ng))
                .build();
    }
}
