package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.card.service.CardStatementFacts;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.mf.service.MfSchemeLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Card statement figures kept from a read: table-shaped summaries, ₹0 and credit balances. */
class CardStatementRecoveryTest {

    private ParsedEmailImporter importer;
    private EmailLLMParserService service;

    @BeforeEach
    void setUp() {
        importer = mock(ParsedEmailImporter.class);
        when(importer.recordCardStatement(anyLong(), any(), anyString())).thenReturn(true);
        service = new EmailLLMParserService(mock(LlmService.class), new LlmJsonParser(new ObjectMapper()), mock(AiAuditService.class),
            mock(MarketDataService.class), mock(MfSchemeLinkService.class), new SenderTrustEvaluator(),
            mock(EmailReviewService.class), importer, mock(CasBalanceSnapshotRepository.class),
            mock(com.marketai.gmail.ledger.FinancialEventLedger.class));
    }

    private static String out(String total, String evidence) {
        return "{\"transactions\":[],\"card_statement\":{\"issuer\":\"IndusInd Bank\",\"card_last4\":\"1234\","
            + "\"due_date\":\"2026-10-12\",\"total_due\":" + total + ",\"minimum_due\":615.00,\"evidence\":\"" + evidence + "\"}}";
    }

    private CardStatementFacts kept() {
        ArgumentCaptor<CardStatementFacts> f = ArgumentCaptor.forClass(CardStatementFacts.class);
        verify(importer).recordCardStatement(eq(2L), f.capture(), eq("m1"));
        return f.getValue();
    }

    @Test
    @DisplayName("a summary printed as a table — labels in one row, figures further on — is kept when every piece is printed")
    void tableShapedSummary() {
        String source = "Total Amount Due Minimum Amount Due Payment Due Date\n" + "Reward points summary ".repeat(40)
            + "\n12,345.00 DR 615.00 12/10/2026";
        assertThat(service.recoverCardStatement(2L, "m1",
            List.of(out("12345.00", "Total Amount Due 12,345.00 DR Minimum Amount Due 615.00 Payment Due Date 12/10/2026")), source)).isTrue();
        assertThat(kept().totalDue()).isEqualByComparingTo("12345.00");
        assertThat(kept().minimumDue()).isEqualByComparingTo("615.00");
    }

    @Test
    @DisplayName("a credit balance and a ₹0 statement are kept")
    void creditAndZero() {
        assertThat(service.recoverCardStatement(2L, "m1",
            List.of(out("-120.00", "Total Amount Due ₹-120.00")), "Statement Date 22/Oct/2026 Total Amount Due ₹-120.00 Payment Due Date 06/Nov/2026")).isTrue();
        assertThat(kept().totalDue()).isEqualByComparingTo("-120.00");

        reset(importer);
        when(importer.recordCardStatement(anyLong(), any(), anyString())).thenReturn(true);
        assertThat(service.recoverCardStatement(2L, "m1",
            List.of(out("0.0", "Total Outstanding (₹) 0.00")), "Summary Total Outstanding (₹) 0.00 Minimum Due 0.00")).isTrue();
        assertThat(kept().totalDue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a figure the document does not print, or a minus sign it does not show, is refused")
    void inventedFiguresRefused() {
        String source = "Total Amount Due Minimum Amount Due\n" + "Reward points summary ".repeat(40) + "\n12,345.00 DR 615.00";
        assertThat(service.recoverCardStatement(2L, "m1",
            List.of(out("13345.00", "Total Amount Due 13,345.00 DR Minimum Amount Due 615.00")), source)).isFalse();
        assertThat(service.recoverCardStatement(2L, "m1",
            List.of(out("-12345.00", "Total Amount Due 12,345.00")), "Total Amount Due 12,345.00")).isFalse();
        verify(importer, never()).recordCardStatement(anyLong(), any(), anyString());
    }
}
