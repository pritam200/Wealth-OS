package com.marketai.ai.audit.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail deliberately persists prompts (that's its value), so redaction is the only
 * thing standing between a quoted credential in a statement email and a permanent DB record
 * of it.
 */
class AiAuditServiceTest {

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
