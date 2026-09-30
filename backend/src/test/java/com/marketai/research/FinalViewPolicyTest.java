package com.marketai.research;

import com.marketai.research.model.DevilsAdvocate;
import com.marketai.research.model.FinalView;
import com.marketai.research.model.QuantAssessment;
import com.marketai.research.model.ResearchReport;
import com.marketai.research.service.FinalViewPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinalViewPolicyTest {

    static QuantAssessment quant(String rating, boolean validated, String status) {
        return new QuantAssessment(rating, rating, validated, validated ? 64 : null, null, "UPTREND", null, List.of(), null, status, "2026-09-29", "s");
    }

    static ResearchReport lean(String a) { return a == null ? null : ResearchReport.builder().actionability(a).build(); }

    @ParameterizedTest(name = "quant {0} (validated={1}), research {2}, review {3} → {4} [{5}]")
    @CsvSource(nullValues = "-", value = {
            "BUY,true,-,-,BUY,QUANT_ONLY",
            "NO_ACTIONABLE_SIGNAL,false,-,-,NO_ACTIONABLE_SIGNAL,QUANT_ONLY",
            "BUY,true,BUY,LOW,BUY,CONFIRMED",
            "BUY,true,BUY,HIGH,CONFLICTING_EVIDENCE,CONFIRMED",
            "BUY,true,SELL,LOW,CONFLICTING_EVIDENCE,OPPOSED",
            "SELL,true,BUY,-,CONFLICTING_EVIDENCE,OPPOSED",
            "BUY,true,HOLD,LOW,NO_ACTIONABLE_SIGNAL,NOT_CONFIRMED",
            "BUY,true,NO_ACTIONABLE_SIGNAL,LOW,NO_ACTIONABLE_SIGNAL,NOT_CONFIRMED",
            "NO_ACTIONABLE_SIGNAL,false,BUY,LOW,NO_ACTIONABLE_SIGNAL,NO_EDGE",
            "NO_ACTIONABLE_SIGNAL,false,SELL,LOW,NO_ACTIONABLE_SIGNAL,NO_EDGE",
            "NO_ACTIONABLE_SIGNAL,false,HOLD,LOW,NO_ACTIONABLE_SIGNAL,NO_EDGE",
            "NO_ACTIONABLE_SIGNAL,false,CONFLICTING_EVIDENCE,LOW,CONFLICTING_EVIDENCE,RESEARCH_FLAG",
            "BUY,true,RESEARCH_REQUIRED,LOW,RESEARCH_REQUIRED,RESEARCH_FLAG",
            "NO_ACTIONABLE_SIGNAL,false,INSUFFICIENT_DATA,-,INSUFFICIENT_DATA,RESEARCH_FLAG",
            // a rating marked BUY but not validated never becomes a call
            "BUY,false,BUY,LOW,NO_ACTIONABLE_SIGNAL,NO_EDGE",
    })
    void table(String q, boolean validated, String research, String risk, String expected, String rule) {
        DevilsAdvocate d = risk == null ? null : DevilsAdvocate.builder().thesisRisk(risk).build();
        FinalView v = FinalViewPolicy.decide(true, quant(q, validated, "OK"), lean(research), d);
        assertThat(v.actionability()).isEqualTo(expected);
        assertThat(v.rule()).isEqualTo(rule);
        assertThat(v.reason()).isNotBlank();
    }

    @Test
    void staleDataGatesEverything() {
        FinalView v = FinalViewPolicy.decide(false, quant("STALE_DATA", false, "STALE_DATA"), lean("BUY"), null);
        assertThat(v.actionability()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(v.reason()).contains("stale");
    }

    @Test
    void researchUnavailableSaysSo() {
        assertThat(FinalViewPolicy.decide(true, quant("NO_ACTIONABLE_SIGNAL", false, "OK"), null, null).reason())
                .startsWith("AI research unavailable");
    }
}
