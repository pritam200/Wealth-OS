package com.marketai.ai.audit.service;

import com.marketai.ai.audit.dto.AiAuditTrailResponse;
import com.marketai.ai.audit.entity.AiAuditTrail;
import com.marketai.ai.audit.repository.AiAuditTrailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The audit trail deliberately persists prompts (that's its value), so redaction is the only
 * thing standing between a quoted credential in a statement email and a permanent DB record
 * of it.
 */
class AiAuditServiceTest {

    private AiAuditTrailRepository repository;
    private AiAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiAuditTrailRepository.class);
        service = new AiAuditService(repository);
    }

    @Test
    void getByReferenceReturnsRowsScopedToUserAndReference() {
        AiAuditTrail row = AiAuditTrail.builder()
                .id(5L).userId(1L).task("EMAIL_EXTRACT").referenceId("gmail-msg-1")
                .provider("ollama").model("qwen2.5:7b")
                .confidence(new BigDecimal("0.9200")).status("ACCEPTED")
                .build();
        when(repository.findByUserIdAndReferenceIdOrderByCreatedAtDesc(1L, "gmail-msg-1"))
                .thenReturn(List.of(row));

        List<AiAuditTrailResponse> result = service.getByReference(1L, "gmail-msg-1");

        assertThat(result).hasSize(1);
        AiAuditTrailResponse dto = result.get(0);
        assertThat(dto.getId()).isEqualTo(5L);
        assertThat(dto.getReferenceId()).isEqualTo("gmail-msg-1");
        assertThat(dto.getStatus()).isEqualTo("ACCEPTED");
        assertThat(dto.getConfidence()).isEqualByComparingTo("0.9200");
    }

    @Test
    void getByReferenceReturnsEmptyWhenNothingRecorded() {
        when(repository.findByUserIdAndReferenceIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        assertThat(service.getByReference(1L, "unknown")).isEmpty();
    }

    @Test
    void redactsStatementPasswordsQuotedInEmailBodies() {
        String prompt = "Your statement is attached. Password: ABCD1234 — please keep it safe.";
        assertThat(AiAuditService.redact(prompt))
            .contains("[REDACTED]")
            .doesNotContain("ABCD1234");
    }

    @Test
    void redactsApiKeysSecretsAndBearerTokens() {
        assertThat(AiAuditService.redact("api_key: sk-abc123def456")).doesNotContain("sk-abc123def456");
        assertThat(AiAuditService.redact("secret=hunter2supersecret")).doesNotContain("hunter2supersecret");
        assertThat(AiAuditService.redact("Authorization: Bearer abcdefghijklmnop123"))
            .doesNotContain("abcdefghijklmnop123");
    }

    @Test
    void redactsGoogleOauthTokensAndJwts() {
        assertThat(AiAuditService.redact("token ya29.a0AfH6SMBxyz-_123abc"))
            .doesNotContain("ya29.a0AfH6SMBxyz");
        assertThat(AiAuditService.redact("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdef"))
            .doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
    }

    @Test
    void leavesLegitimateFinancialContentIntact() {
        // Redaction must not eat the actual data the audit row exists to explain.
        String prompt = "Debited Rs.2,500 at SWIGGY on 2026-03-04 from HDFC Bank a/c XX1234";
        assertThat(AiAuditService.redact(prompt)).isEqualTo(prompt);
    }

    @Test
    void truncatesOversizedPromptsRatherThanFailingTheWrite() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30_000; i++) sb.append('x');
        String out = AiAuditService.redact(sb.toString());
        assertThat(out).hasSizeLessThan(30_000).endsWith("…[truncated]");
    }

    @Test
    void handlesNull() {
        assertThat(AiAuditService.redact(null)).isNull();
    }
}
