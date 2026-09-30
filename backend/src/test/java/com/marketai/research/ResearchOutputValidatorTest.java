package com.marketai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.research.model.*;
import com.marketai.research.service.ResearchOutputValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ResearchOutputValidatorTest {

    static final List<Fact> FACTS = List.of(
            Fact.of("MARKET", "DATA", "Last close", 100.0, "₹", "2026-09-29", "Yahoo").withId("F1"),
            Fact.of("TECHNICAL", "CALCULATION", "RSI", 55.0, null, "2026-09-29", "calc").withId("F2"),
            Fact.of("PORTFOLIO", "PORTFOLIO", "Shares held", 10.0, "shares", "2026-09-29", "holdings").withId("F3"),
            Fact.text("SIGNAL", "MODEL", "Signal", "NO_ACTIONABLE_SIGNAL", "2026-09-29", "engine").withId("F4"));
    static final List<Evidence> EVIDENCE = List.of(
            new Evidence("E1", "FILING", "PRIMARY", "NSE", "Results", LocalDateTime.now(), null, LocalDateTime.now(), "HIGH", null),
            new Evidence("E2", "NEWS", "RELIABLE", "Mint", "Headline", LocalDateTime.now(), null, LocalDateTime.now(), "MEDIUM", null));

    private final ObjectMapper m = new ObjectMapper();

    JsonNode j(String s) throws Exception { return m.readTree(s); }

    @Test
    @DisplayName("basis comes from what a claim cites; unknown ids are dropped; nothing cited is LLM interpretation")
    void basisAndCitations() throws Exception {
        ResearchOutputValidator v = new ResearchOutputValidator(FACTS, EVIDENCE);
        ResearchReport r = v.report(j("""
            {"executiveSummary":{"text":"Filing and price agree.","evidence":["E1","F1","F99"]},
             "technicalAssessment":{"text":"RSI mid-range.","evidence":["F2"]},
             "portfolioImpact":{"text":"Small position.","evidence":["F3"]},
             "newsAssessment":{"text":"Coverage is thin.","evidence":[]},
             "researchConclusion":{"text":"No edge.","evidence":["F4","E2"]},
             "actionability":"no actionable signal","evidenceQuality":"medium"}"""));
        assertThat(r.getExecutiveSummary().basis()).isEqualTo("FILING+DATA");
        assertThat(r.getExecutiveSummary().evidence()).containsExactly("E1", "F1");
        assertThat(r.getTechnicalAssessment().basis()).isEqualTo("CALCULATION");
        assertThat(r.getPortfolioImpact().basis()).isEqualTo("PORTFOLIO");
        assertThat(r.getNewsAssessment().basis()).isEqualTo("LLM_INTERPRETATION");
        assertThat(r.getResearchConclusion().basis()).isEqualTo("MODEL+NEWS");
        assertThat(r.getActionability()).isEqualTo("NO_ACTIONABLE_SIGNAL");
        assertThat(r.getEvidenceQuality()).isEqualTo("MEDIUM");
        assertThat(r.getSources()).containsExactly("E1", "E2");
        assertThat(v.droppedCitations()).containsExactly("F99");
        // every cross-check question is present, unanswered ones as UNKNOWN
        assertThat(r.getCrossChecks()).hasSize(CrossCheck.QUESTIONS.size()).allMatch(c -> c.answer().equals("UNKNOWN"));
    }

    @Test
    @DisplayName("a disallowed actionability is not passed through as a call")
    void enumsValidated() throws Exception {
        ResearchOutputValidator v = new ResearchOutputValidator(FACTS, EVIDENCE);
        ResearchReport r = v.report(j("""
            {"executiveSummary":"x","researchConclusion":"y","actionability":"STRONG BUY","evidenceQuality":"great",
             "crossChecks":[{"question":"NEWS_VS_TREND","answer":"MAYBE","text":"t","evidence":["E2"]}]}"""));
        assertThat(r.getActionability()).isEqualTo("RESEARCH_REQUIRED");
        assertThat(r.getEvidenceQuality()).isEqualTo("LOW");
        assertThat(r.getCrossChecks()).filteredOn(c -> c.question().equals("NEWS_VS_TREND")).first()
                .satisfies(c -> { assertThat(c.answer()).isEqualTo("UNKNOWN"); assertThat(c.explanation().basis()).isEqualTo("NEWS"); });
        assertThat(v.notes()).hasSize(2);
    }

    @Test
    @DisplayName("missing required sections are rejected")
    void requiredSections() {
        ResearchOutputValidator v = new ResearchOutputValidator(FACTS, EVIDENCE);
        assertThatThrownBy(() -> v.report(j("{\"executiveSummary\":\"x\",\"actionability\":\"HOLD\"}")))
                .isInstanceOf(ResearchOutputValidator.Invalid.class).hasMessageContaining("researchConclusion");
        assertThatThrownBy(() -> v.report(null)).isInstanceOf(ResearchOutputValidator.Invalid.class);
        assertThatThrownBy(() -> v.devilsAdvocate(j("{\"overlookedRisks\":[]}"))).hasMessageContaining("thesisRisk");
    }

    @Test
    @DisplayName("citing a whole range of ids is capped, and the cap is recorded")
    void citationSpamCapped() throws Exception {
        ResearchOutputValidator v = new ResearchOutputValidator(FACTS, EVIDENCE);
        ResearchReport r = v.report(j("""
            {"executiveSummary":{"text":"x","evidence":["F1","F2","F3","F4","E1","E2","F1"]},
             "researchConclusion":{"text":"y","evidence":["F1","F2","F3","F4","E1","E2","F2","F3"]},"actionability":"HOLD"}"""));
        assertThat(r.getExecutiveSummary().evidence()).hasSize(6);
        assertThat(v.notes()).noneMatch(n -> n.contains("cited more than"));
        ResearchOutputValidator v2 = new ResearchOutputValidator(FACTS, List.of(EVIDENCE.get(0), EVIDENCE.get(1),
                new Evidence("E3", "NEWS", "NEWS", "X", "t", null, null, null, "LOW", null)));
        ResearchReport r2 = v2.report(j("""
            {"executiveSummary":{"text":"x","evidence":["F1","F2","F3","F4","E1","E2","E3"]},
             "researchConclusion":"y","actionability":"HOLD"}"""));
        assertThat(r2.getExecutiveSummary().evidence()).containsExactly("F1", "F2", "F3", "F4", "E1", "E2");
        assertThat(r2.getSources()).containsExactly("E1", "E2");
        assertThat(v2.notes()).anyMatch(n -> n.contains("cited more than 6"));
    }

    @Test
    void devilsAdvocateParsed() throws Exception {
        ResearchOutputValidator v = new ResearchOutputValidator(FACTS, EVIDENCE);
        DevilsAdvocate d = v.devilsAdvocate(j("""
            {"overlookedRisks":[{"text":"Results due.","evidence":["E1"]},"none found"],"thesisRisk":"medium",
             "verdict":{"text":"Holds up.","evidence":[]}}"""));
        assertThat(d.getOverlookedRisks()).hasSize(1);
        assertThat(d.getOverlookedRisks().get(0).basis()).isEqualTo("FILING");
        assertThat(d.getThesisRisk()).isEqualTo("MEDIUM");
        assertThat(d.getContradictoryEvidence()).isEmpty();
    }
}
