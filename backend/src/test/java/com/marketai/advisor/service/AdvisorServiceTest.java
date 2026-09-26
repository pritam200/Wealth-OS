package com.marketai.advisor.service;

import com.marketai.advisor.dto.AdvisorAskRequest;
import com.marketai.advisor.dto.AdvisorAskResponse;
import com.marketai.advisor.dto.AdvisorTool;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.expense.service.ExpenseService;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.reminder.service.ReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The advisor must never let a chat answer state a figure the LLM invented: the model only
 * ever selects a tool name, and every number in the answer comes straight back out of the
 * real service ({@link PortfolioContextService} etc). These tests pin that down, plus the
 * house-style requirement that an unavailable router degrades to a clear message, not a crash.
 */
class AdvisorServiceTest {

    private LlmProviderRouter llm;
    private AiAuditService audit;
    private PortfolioContextService portfolioContextService;
    private ExpenseService expenseService;
    private ReminderService reminderService;
    private AdvisorService service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmProviderRouter.class);
        audit = mock(AiAuditService.class);
        portfolioContextService = mock(PortfolioContextService.class);
        expenseService = mock(ExpenseService.class);
        reminderService = mock(ReminderService.class);
        service = new AdvisorService(llm, new LlmJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
            audit, portfolioContextService, expenseService, reminderService);
    }

    @Test
    @DisplayName("a net-worth question resolves to the NET_WORTH tool and answers with the real computed figure")
    void netWorthQuestionResolvesToRealFigure() {
        when(llm.isEnabled()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"NET_WORTH\"}").provider("ollama").model("qwen2.5:7b").build());

        PortfolioContext ctx = PortfolioContext.builder()
            .netWorth(new BigDecimal("1234567.00"))
            .totalAssets(new BigDecimal("1300000.00"))
            .equityPercent(60.0).debtPercent(30.0)
            .dataQuality("FULL").dataGaps(Collections.emptyList())
            .build();
        when(portfolioContextService.build(1L)).thenReturn(ctx);

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What is my net worth?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isTrue();
        assertThat(resp.getTool()).isEqualTo(AdvisorTool.NET_WORTH);
        assertThat(resp.getAnswer()).contains("1234567");
        assertThat(resp.getGroundedData()).containsEntry("netWorth", ctx.getNetWorth());
        verifyNoInteractions(expenseService, reminderService);
    }

    @Test
    @DisplayName("router unavailable degrades to a clear message instead of throwing")
    void routerUnavailableDegradesGracefully() {
        when(llm.isEnabled()).thenReturn(false);

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What is my net worth?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isFalse();
        assertThat(resp.getAnswer()).isEqualTo(LlmProviderRouter.NONE_AVAILABLE);
        assertThat(resp.getTool()).isEqualTo(AdvisorTool.UNKNOWN);
        verifyNoInteractions(portfolioContextService, expenseService, reminderService);
    }

    @Test
    @DisplayName("classify throwing LlmUnavailableException mid-call also degrades gracefully, not a crash")
    void classifyThrowingDegradesGracefully() {
        when(llm.isEnabled()).thenReturn(true);
        when(llm.complete(anyString(), anyString()))
            .thenThrow(new LlmUnavailableException("model unreachable"));

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What are my upcoming bills?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isFalse();
        assertThat(resp.getAnswer()).isEqualTo(LlmProviderRouter.NONE_AVAILABLE);
    }

    @Test
    @DisplayName("an unrecognised tool name from the model is treated as UNKNOWN, not passed through")
    void unknownToolIsHandledSafely() {
        when(llm.isEnabled()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"SOMETHING_MADE_UP\"}").provider("ollama").model("qwen2.5:7b").build());

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What's the weather?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isTrue();
        assertThat(resp.getTool()).isEqualTo(AdvisorTool.UNKNOWN);
        verifyNoInteractions(portfolioContextService, expenseService, reminderService);
    }
}
