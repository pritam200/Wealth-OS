package com.marketai.ai.service;

import com.marketai.ai.dto.AiRequest;
import com.marketai.ai.dto.AiResponse;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.repository.AiHistoryRepository;
import com.marketai.auth.entity.User;
import com.marketai.market.service.MarketDataService;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.research.model.*;
import com.marketai.research.service.NumberGuard;
import com.marketai.research.service.ResearchOrchestrator;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The AI Advisor's stock answer is built on the research engine's verified context, not its own. */
class AiServiceResearchTest {

    @Test
    @DisplayName("analyseStock uses ResearchOrchestrator's context (stored research only) and guards the answer's figures")
    void usesTheOneEngine() {
        LlmService llm = mock(LlmService.class);
        ResearchOrchestrator research = mock(ResearchOrchestrator.class);
        TechnicalIndicatorService technical = mock(TechnicalIndicatorService.class);
        AiService svc = new AiService(llm, mock(AiHistoryRepository.class), mock(MarketDataService.class), technical,
                mock(PortfolioService.class), research);
        ResearchResult rr = ResearchResult.builder().subjectType("STOCK").symbol("TEST").displayName("Test Ltd").marketDate("2026-09-29")
                .facts(List.of(Fact.of("MARKET", "DATA", "Last daily close", 100.0, "₹", "2026-09-29", "Yahoo").withId("F1")))
                .evidence(List.of()).sources(List.of()).missingData(List.of())
                .finalView(new FinalView("NO_ACTIONABLE_SIGNAL", "No validated edge.", "QUANT_ONLY", "NO_ACTIONABLE_SIGNAL", null))
                .build();
        when(research.stock(eq("TEST"), isNull(), eq(1L), eq(ResearchOrchestrator.Mode.CACHED))).thenReturn(rr);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder().text("It closed at ₹100.00; fair value ₹140.").build());

        AiRequest req = new AiRequest();
        req.setSymbol("TEST");
        req.setPrompt("Is it cheap?");
        AiResponse out = svc.analyseStock(req, User.builder().id(1L).email("someone@example.com").build());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), any(), prompt.capture());
        assertThat(prompt.getValue()).contains("[F1] Last daily close: ₹100.00").contains("Final view (code-decided): NO_ACTIONABLE_SIGNAL");
        assertThat(out.getSummary()).contains("₹100.00").doesNotContain("140").contains(NumberGuard.MARKER);
        verifyNoInteractions(technical);
    }
}
