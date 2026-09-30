package com.marketai.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.llm.LlmTask;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.prompt.PromptLibrary;
import com.marketai.research.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The research engine end to end with the model mocked: what reaches the model, what is kept
 * from its reply, and what happens when it fails. Covers the acceptance cases for research:
 * LLM failure, stale data, missing fundamentals, conflicting evidence, news refresh,
 * traceability, invented numbers, and the deterministic analysis being left untouched.
 */
class ResearchOrchestratorTest {

    static final LlmTask T = LlmTask.FINANCIAL_ANALYSIS;

    LlmService llm;
    ResearchCache cache;
    ResearchOrchestrator orch;
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    static final String ANALYST_BUY = """
        {"executiveSummary":{"text":"Price at ₹100.00 with RSI 55; a target of ₹180 looks likely.","evidence":["F1","F2"]},
         "technicalAssessment":{"text":"Momentum is neutral.","evidence":["F2","F77"]},
         "newsAssessment":{"text":"The results filing is the main event.","evidence":["E1"]},
         "bullCase":{"text":"Results beat.","evidence":["E1"]},"baseCase":"Range-bound.","bearCase":{"text":"Guidance cut.","evidence":["E2"]},
         "keyRisks":[{"text":"Results due soon.","evidence":["E1"]}],
         "missingInformation":["ROCE"],
         "researchConclusion":{"text":"Research supports the BUY.","evidence":["F4","E1"]},
         "actionability":"BUY","evidenceQuality":"MEDIUM"}""";
    static final String DEVIL_LOW = """
        {"overlookedRisks":[{"text":"Sector is weak.","evidence":["F5"]}],"thesisRisk":"LOW","verdict":{"text":"Holds.","evidence":[]}}""";

    @BeforeEach
    void setUp() {
        llm = mock(LlmService.class);
        cache = mock(ResearchCache.class);
        orch = new ResearchOrchestrator(mock(ResearchContextBuilder.class), llm, new LlmJsonParser(mapper), cache, mapper);
        when(llm.describe(T)).thenReturn("ollama:qwen2.5:14b");
        when(llm.isAvailable(T)).thenReturn(true);
        when(llm.webResearch(eq(T), any(), anyString())).thenReturn(Optional.empty());
        when(cache.find(any())).thenReturn(Optional.empty());
        when(cache.latest(any(), any(), any())).thenReturn(Optional.empty());
    }

    static QuantAssessment quant(String rating, boolean validated, String status) {
        return new QuantAssessment(rating, rating, validated, validated ? 64 : null, "summary", "UPTREND", "4/6",
                List.of("20D: 90% model range ₹90.00–₹110.00 (held in 89.1% of past windows)"), "calibrated", status, "2026-09-29", "q");
    }

    static ResearchContext ctx(QuantAssessment q, List<Fact> extraFacts, List<Evidence> evidence) {
        List<Fact> facts = new ArrayList<>(List.of(
                Fact.of("MARKET", "DATA", "Last daily close", 100.0, "₹", "2026-09-29", "Yahoo daily bars"),
                Fact.of("TECHNICAL", "CALCULATION", "RSI(14)", 55.0, null, "2026-09-29", "calc"),
                Fact.of("FORECAST", "MODEL", "20D 90% model range — high", 110.0, "₹", "2026-09-29", "model"),
                Fact.text("SIGNAL", "MODEL", "Signal shown", q.rating(), "2026-09-29", "SignalEngine"),
                Fact.of("SECTOR", "CALCULATION", "Nifty IT — 20-session change", -4.0, "%", "2026-09-29", "calc")));
        facts.addAll(extraFacts);
        List<Fact> numbered = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) numbered.add(facts.get(i).withId("F" + (i + 1)));
        List<Evidence> ev = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) ev.add(evidence.get(i).withId("E" + (i + 1)));
        String hash = ResearchContextBuilder.hash("STOCK", "TEST", LocalDate.of(2026, 9, 29), numbered, ev, q, 7L);
        return new ResearchContext("STOCK", "TEST", "Test Ltd", "2026-09-29", numbered, ev,
                List.of(SourceStatus.ok("NSE announcements", 1), SourceStatus.unavailable("NSE corporate actions", "NSE unreachable: SSLHandshakeException")),
                numbered.stream().filter(f -> !f.available()).map(Fact::label).toList(), q, 7L, hash);
    }

    static List<Evidence> filings() {
        return List.of(
                new Evidence(null, "FILING", "PRIMARY", "NSE", "Outcome of board meeting — Q2 results", LocalDateTime.of(2026, 9, 25, 18, 0), null, LocalDateTime.now(), "HIGH", null),
                new Evidence(null, "NEWS", "RELIABLE", "Mint", "Test Ltd cuts guidance", LocalDateTime.of(2026, 9, 26, 9, 0), "https://www.livemint.com/x", LocalDateTime.now(), "HIGH", null));
    }

    void analystReplies(String... replies) {
        var stub = when(llm.complete(eq(T), eq(PromptLibrary.RESEARCH_ANALYST), anyString()));
        for (String r : replies) stub = stub.thenReturn(completion(r));
    }

    void devilReplies(String r) {
        when(llm.complete(eq(T), eq(PromptLibrary.RESEARCH_DEVILS_ADVOCATE), anyString())).thenReturn(completion(r));
    }

    static LlmCompletion completion(String text) {
        return LlmCompletion.builder().text(text).provider("ollama").model("qwen2.5:14b")
                .promptVersion(PromptLibrary.RESEARCH_ANALYST.tag()).build();
    }

    @Test
    @DisplayName("happy path: two passes, validated, traceable, invented figures removed, stored, quant untouched")
    void happyPath() {
        QuantAssessment q = quant("BUY", true, "OK");
        ResearchContext c = ctx(q, List.of(), filings());
        analystReplies(ANALYST_BUY);
        devilReplies(DEVIL_LOW);

        ResearchResult r = orch.run(c, ResearchOrchestrator.Mode.AUTO);

        assertThat(r.getStatus()).isEqualTo("OK");
        assertThat(r.getProvider()).isEqualTo("ollama");
        assertThat(r.getModel()).isEqualTo("qwen2.5:14b");
        assertThat(r.getAnalystPromptVersion()).isEqualTo("research-analyst-v2");
        assertThat(r.getReviewPromptVersion()).isEqualTo("research-devils-advocate-v2");
        assertThat(r.getDataSnapshotHash()).isEqualTo(c.snapshotHash());
        // the LLM cannot invent numbers: the target is gone, the verified close and RSI remain
        assertThat(r.getReport().getExecutiveSummary().text()).contains("₹100.00").contains("RSI 55")
                .doesNotContain("180").contains(NumberGuard.MARKER);
        assertThat(r.getGuard().removedFigures()).containsExactly("₹180");
        assertThat(r.getGuard().droppedCitations()).containsExactly("F77");
        // traceability: every claim carries a basis derived from what it cites
        assertThat(r.getReport().getExecutiveSummary().basis()).isEqualTo("DATA+CALCULATION");
        assertThat(r.getReport().getNewsAssessment().basis()).isEqualTo("FILING");
        assertThat(r.getReport().getBaseCase().basis()).isEqualTo("LLM_INTERPRETATION");
        assertThat(r.getDevilsAdvocate().getOverlookedRisks().get(0).basis()).isEqualTo("CALCULATION");
        // web search is recorded as not supported for an Ollama route, not silently skipped
        assertThat(r.getSources()).anySatisfy(s -> { assertThat(s.source()).contains("Web research"); assertThat(s.status()).isEqualTo("NOT_SUPPORTED"); });
        // validated BUY + research BUY + low thesis risk
        assertThat(r.getFinalView().actionability()).isEqualTo("BUY");
        // the deterministic analysis is exactly what was passed in
        assertThat(r.getQuant()).isSameAs(q);
        assertThat(r.getFacts()).isEqualTo(c.facts());
        verify(cache).save(eq(r), eq(ResearchOrchestrator.PROMPT_VERSION));
    }

    @Test
    @DisplayName("what the model sees: numbered facts, UNAVAILABLE for missing fundamentals, source status")
    void promptContents() {
        ResearchContext c = ctx(quant("NO_ACTIONABLE_SIGNAL", false, "OK"),
                List.of(Fact.missing("FUNDAMENTAL", "Fundamentals", "The quote and fundamentals could not be fetched")), filings());
        analystReplies(ANALYST_BUY);
        devilReplies(DEVIL_LOW);
        orch.run(c, ResearchOrchestrator.Mode.AUTO);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(eq(T), eq(PromptLibrary.RESEARCH_ANALYST), prompt.capture());
        assertThat(prompt.getValue())
                .contains("[F1] Last daily close: ₹100.00 (as of 2026-09-29) — DATA, Yahoo daily bars")
                .contains("[F6] Fundamentals: UNAVAILABLE — The quote and fundamentals could not be fetched")
                .contains("[E1] FILING, PRIMARY, NSE, 2026-09-25")
                .contains("NSE corporate actions: UNAVAILABLE")
                .contains("Rating: NO_ACTIONABLE_SIGNAL (not validated)");
    }

    @Test
    @DisplayName("research leaning BUY without a validated quant edge does not become a call")
    void researchAloneIsNotACall() {
        analystReplies(ANALYST_BUY);
        devilReplies(DEVIL_LOW);
        ResearchResult r = orch.run(ctx(quant("NO_ACTIONABLE_SIGNAL", false, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(r.getReport().getActionability()).isEqualTo("BUY");
        assertThat(r.getFinalView().actionability()).isEqualTo("NO_ACTIONABLE_SIGNAL");
        assertThat(r.getFinalView().rule()).isEqualTo("NO_EDGE");
    }

    @Test
    @DisplayName("conflicting evidence: validated BUY but research leans SELL")
    void conflictingEvidence() {
        analystReplies(ANALYST_BUY.replace("\"actionability\":\"BUY\"", "\"actionability\":\"SELL\""));
        devilReplies(DEVIL_LOW);
        ResearchResult r = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(r.getFinalView().actionability()).isEqualTo("CONFLICTING_EVIDENCE");
        assertThat(r.getFinalView().rule()).isEqualTo("OPPOSED");
    }

    @Test
    @DisplayName("stale market data: no view, whatever the research says")
    void staleData() {
        analystReplies(ANALYST_BUY);
        devilReplies(DEVIL_LOW);
        ResearchResult r = orch.run(ctx(quant("STALE_DATA", false, "STALE_DATA"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(r.getFinalView().actionability()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.getFinalView().rule()).isEqualTo("DATA_GATE");
    }

    @Test
    @DisplayName("invalid JSON is retried once, then research is reported unavailable")
    void invalidJson() {
        analystReplies("not json", ANALYST_BUY);
        devilReplies(DEVIL_LOW);
        ResearchResult ok = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(ok.getStatus()).isEqualTo("OK");
        verify(llm, times(2)).complete(eq(T), eq(PromptLibrary.RESEARCH_ANALYST), anyString());

        reset(llm);
        setUp();
        analystReplies("{\"foo\":1}", "still not it");
        ResearchResult bad = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(bad.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(bad.getReport()).isNull();
        assertThat(bad.getFinalView().rule()).isEqualTo("QUANT_ONLY");
        verify(cache, never()).save(any(), any());
    }

    @Test
    @DisplayName("LLM failure: deterministic view stands, last research shown labelled with its date")
    void llmFailureShowsLastResearch() {
        ResearchResult previous = ResearchResult.builder().symbol("TEST").subjectType("STOCK").status("OK")
                .researchTimestamp(LocalDateTime.of(2026, 9, 28, 16, 5)).marketDate("2026-09-28").dataSnapshotHash("old")
                .provider("gemini").model("gemini-2.5-flash")
                .report(ResearchReport.builder().actionability("BUY").build()).build();
        when(cache.latest("STOCK", "TEST", 7L)).thenReturn(Optional.of(previous));
        when(llm.complete(eq(T), eq(PromptLibrary.RESEARCH_ANALYST), anyString())).thenThrow(new LlmUnavailableException("Ollama is not reachable"));

        QuantAssessment q = quant("NO_ACTIONABLE_SIGNAL", false, "OK");
        ResearchResult r = orch.run(ctx(q, List.of(), filings()), ResearchOrchestrator.Mode.AUTO);

        assertThat(r.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(r.getStatusReason()).startsWith("AI research unavailable");
        assertThat(r.isStale()).isTrue();
        assertThat(r.getStaleReason()).contains("Last research: 28 Sep 2026 16:05").contains("2026-09-28");
        assertThat(r.getPrevious().getReport().getActionability()).isEqualTo("BUY");
        assertThat(r.getReport()).isNull();
        // yesterday's BUY does not leak into today's view
        assertThat(r.getFinalView().actionability()).isEqualTo("NO_ACTIONABLE_SIGNAL");
        assertThat(r.getQuant()).isSameAs(q);
    }

    @Test
    @DisplayName("devil's-advocate failure leaves the research as PARTIAL")
    void reviewFailure() {
        analystReplies(ANALYST_BUY);
        when(llm.complete(eq(T), eq(PromptLibrary.RESEARCH_DEVILS_ADVOCATE), anyString())).thenThrow(new LlmUnavailableException("timeout"));
        ResearchResult r = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(r.getStatus()).isEqualTo("PARTIAL");
        assertThat(r.getDevilsAdvocate()).isNull();
        assertThat(r.getReport()).isNotNull();
    }

    @Test
    @DisplayName("cache: exact hit serves stored research without a model call; CACHED mode never calls one")
    void caching() {
        ResearchContext c = ctx(quant("BUY", true, "OK"), List.of(), filings());
        ResearchResult stored = ResearchResult.builder().symbol("TEST").status("OK").build();
        when(cache.find(new ResearchCache.Key("STOCK", "TEST", 7L, "2026-09-29", c.snapshotHash(),
                ResearchOrchestrator.PROMPT_VERSION, "ollama", "qwen2.5:14b"))).thenReturn(Optional.of(stored));
        assertThat(orch.run(c, ResearchOrchestrator.Mode.AUTO).isFromCache()).isTrue();

        when(cache.find(any())).thenReturn(Optional.empty());
        ResearchResult none = orch.run(c, ResearchOrchestrator.Mode.CACHED);
        assertThat(none.getStatus()).isEqualTo("NOT_RUN");
        verify(llm, never()).complete(any(), any(), anyString());
        verify(llm, never()).webResearch(any(), any(), anyString());
    }

    @Test
    @DisplayName("news refresh: a new headline changes the snapshot hash, so stored research is not reused")
    void newsChangesHash() {
        QuantAssessment q = quant("BUY", true, "OK");
        List<Evidence> more = new ArrayList<>(filings());
        more.add(new Evidence(null, "NEWS", "NEWS", "Some Site", "New headline", LocalDateTime.of(2026, 9, 29, 20, 0), null, LocalDateTime.now(), "HIGH", null));
        assertThat(ctx(q, List.of(), more).snapshotHash()).isNotEqualTo(ctx(q, List.of(), filings()).snapshotHash());
        // the retrieval time alone does not change it
        List<Evidence> refetched = filings().stream().map(e -> new Evidence(null, e.kind(), e.tier(), e.source(), e.title(), e.publishedAt(), e.url(),
                LocalDateTime.now().plusHours(3), e.relevance(), e.detail())).toList();
        assertThat(ctx(q, List.of(), refetched).snapshotHash()).isEqualTo(ctx(q, List.of(), filings()).snapshotHash());
    }

    @Test
    @DisplayName("AI switched off: DISABLED, quantitative view only")
    void disabled() {
        when(llm.isAvailable(T)).thenReturn(false);
        ResearchResult r = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);
        assertThat(r.getStatus()).isEqualTo("DISABLED");
        assertThat(r.getFinalView().actionability()).isEqualTo("BUY");
        assertThat(r.getFinalView().reason()).startsWith("AI research unavailable");
    }

    @Test
    @DisplayName("Gemini web research: grounded statements become dated, tiered, citable evidence")
    void webResearchEvidence() {
        LlmCompletion web = LlmCompletion.builder().text("- [2026-09-27] Reuters: Test Ltd wins order.")
                .grounding(new LlmCompletion.Grounding(List.of("Test Ltd news"),
                        List.of(new LlmCompletion.Source("reuters.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc")),
                        List.of(new LlmCompletion.Support("- [2026-09-27] Reuters: Test Ltd wins order.", List.of(0)))))
                .build();
        when(llm.describe(T)).thenReturn("gemini:gemini-2.5-flash");
        when(llm.webResearch(eq(T), eq(PromptLibrary.RESEARCH_WEB), anyString())).thenReturn(Optional.of(web));
        analystReplies(ANALYST_BUY.replace("\"evidence\":[\"E1\"]}", "\"evidence\":[\"E3\"]}"));
        devilReplies(DEVIL_LOW);

        ResearchResult r = orch.run(ctx(quant("BUY", true, "OK"), List.of(), filings()), ResearchOrchestrator.Mode.AUTO);

        Evidence w = r.getEvidence().get(2);
        assertThat(w.id()).isEqualTo("E3");
        assertThat(w.kind()).isEqualTo("WEB");
        assertThat(w.tier()).isEqualTo("RELIABLE");
        assertThat(w.publishedAt()).isEqualTo(LocalDateTime.of(2026, 9, 27, 0, 0));
        assertThat(w.title()).isEqualTo("Reuters: Test Ltd wins order.");
        assertThat(r.getWebSearchQueries()).containsExactly("Test Ltd news");
        assertThat(r.getWebPromptVersion()).isEqualTo("research-web-v1");
        assertThat(r.getReport().getNewsAssessment().evidence()).containsExactly("E3");
        assertThat(r.getReport().getNewsAssessment().basis()).isEqualTo("NEWS");
    }
}
