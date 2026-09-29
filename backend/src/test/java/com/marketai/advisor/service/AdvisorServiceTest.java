package com.marketai.advisor.service;

import com.marketai.advisor.dto.AdvisorAskRequest;
import com.marketai.advisor.dto.AdvisorAskResponse;
import com.marketai.advisor.dto.AdvisorTool;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
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

    private LlmService llm;
    private AiAuditService audit;
    private PortfolioContextService portfolioContextService;
    private ExpenseService expenseService;
    private ReminderService reminderService;
    private LedgerInvestigator investigator;
    private AdvisorService service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmService.class);
        audit = mock(AiAuditService.class);
        portfolioContextService = mock(PortfolioContextService.class);
        expenseService = mock(ExpenseService.class);
        reminderService = mock(ReminderService.class);
        investigator = mock(LedgerInvestigator.class);
        service = new AdvisorService(llm, new LlmJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
            audit, portfolioContextService, expenseService, reminderService, investigator);
    }

    @Test
    @DisplayName("a net-worth question resolves to the NET_WORTH tool and answers with the real computed figure")
    void netWorthQuestionResolvesToRealFigure() {
        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
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
        when(llm.isAvailable(any())).thenReturn(false);

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What is my net worth?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isFalse();
        assertThat(resp.getAnswer()).isEqualTo(LlmService.NONE_AVAILABLE);
        assertThat(resp.getTool()).isEqualTo(AdvisorTool.UNKNOWN);
        verifyNoInteractions(portfolioContextService, expenseService, reminderService);
    }

    @Test
    @DisplayName("classify throwing LlmUnavailableException mid-call also degrades gracefully, not a crash")
    void classifyThrowingDegradesGracefully() {
        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString()))
            .thenThrow(new LlmUnavailableException("model unreachable"));

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What are my upcoming bills?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isFalse();
        assertThat(resp.getAnswer()).isEqualTo(LlmService.NONE_AVAILABLE);
    }

    @Test
    @DisplayName("an unrecognised tool name from the model is treated as UNKNOWN, not passed through")
    void unknownToolIsHandledSafely() {
        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"SOMETHING_MADE_UP\"}").provider("ollama").model("qwen2.5:7b").build());

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("What's the weather?");

        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.isAvailable()).isTrue();
        assertThat(resp.getTool()).isEqualTo(AdvisorTool.UNKNOWN);
        verifyNoInteractions(portfolioContextService, expenseService, reminderService);
    }

    @Test
    @DisplayName("spend totals leave out card-bill payments and self transfers")
    void recentExpensesExcludeAccountTransfers() {
        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"RECENT_EXPENSES\"}").provider("gemini").model("m").build());
        when(expenseService.listExpenses(1L)).thenReturn(java.util.List.of(
            com.marketai.expense.dto.ExpenseResponse.builder().amount(new BigDecimal("40000")).category("Shopping").build(),
            com.marketai.expense.dto.ExpenseResponse.builder().amount(new BigDecimal("40000")).category("Account Transfer").build()));

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("How much did I spend?");
        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.getGroundedData().get("totalLast90Days")).isEqualTo(new BigDecimal("40000"));
        assertThat(resp.getAnswer()).contains("₹40000");
    }

    @Test
    @DisplayName("an investigation question is answered by the ledger investigator, with the provider it names as the filter")
    void importedFromProviderUsesSubjectFromQuestion() {
        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"IMPORTED_TRANSACTIONS\", \"subject\": \"HDFC\", \"scope\": \"ALL\"}").provider("ollama").model("m").build());
        when(investigator.importedTransactions(eq(1L), eq("HDFC"), any())).thenReturn(new LedgerInvestigator.Answer(
            "2 records imported", java.util.Map.of(), List.of(com.marketai.advisor.dto.AdvisorEvidence.builder().kind("expense").id(5L).build())));

        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("Show all transactions imported from HDFC this month");
        AdvisorAskResponse resp = service.ask(1L, req);

        assertThat(resp.getTool()).isEqualTo(AdvisorTool.IMPORTED_TRANSACTIONS);
        assertThat(resp.getAnswer()).isEqualTo("2 records imported");
        assertThat(resp.getEvidence()).hasSize(1);
    }

    @Test
    @DisplayName("a subject the question never mentions, or a generic word, is not used as a filter")
    void subjectMustComeFromTheQuestion() {
        assertThat(AdvisorService.cleanSubject("ICICI", "Show all transactions imported from HDFC")).isNull();
        assertThat(AdvisorService.cleanSubject("mutual funds", "Show the documents behind my mutual funds")).isNull();
        assertThat(AdvisorService.cleanSubject("hdfc", "Why is my HDFC MF value different?")).isEqualTo("hdfc");
        assertThat(AdvisorService.cleanSubject("HDFC MF", "Why is my HDFC MF value different?")).isEqualTo("HDFC");
        assertThat(AdvisorService.cleanSubject("null", "anything")).isNull();

        when(llm.isAvailable(any())).thenReturn(true);
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"tool\": \"HOLDING_SOURCES\", \"subject\": \"Axis\", \"scope\": \"MF\"}").provider("ollama").model("m").build());
        when(investigator.holdingSources(anyLong(), any(), any())).thenReturn(new LedgerInvestigator.Answer("x", java.util.Map.of(), List.of()));
        AdvisorAskRequest req = new AdvisorAskRequest();
        req.setQuestion("Show every source document behind my current MF holdings");
        service.ask(1L, req);
        verify(investigator).holdingSources(1L, null, LedgerInvestigator.Scope.MF);
    }
}
