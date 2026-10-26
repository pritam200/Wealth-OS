package com.marketai.gmail.service;

import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The small decisions the sync makes before any email is read. */
class EmailHelpersTest {

    @Test
    @DisplayName("a second attachment with the same name gets a name of its own")
    void sameNamedAttachmentsKeptApart() {
        var out = GmailSyncService.distinctNames(List.of(
            new GmailClientService.PdfAttachmentRef("a", "statement.pdf"),
            new GmailClientService.PdfAttachmentRef("b", "Statement.pdf"),
            new GmailClientService.PdfAttachmentRef("c", "note")));
        assertThat(out).extracting(r -> r.filename).containsExactly("statement.pdf", "Statement (2).pdf", "note");
        assertThat(out).extracting(r -> r.attachmentId).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("an excluded email is read again once the pattern that excluded it is removed")
    void exclusionFollowsItsPattern() {
        ProcessedEmail e = ProcessedEmail.builder().type("EXCLUDED")
            .resultSummary("Matches the excluded sender/pattern \"promo@x.com\"").build();
        assertThat(GmailSyncService.stillExcluded(e, List.of("PROMO@x.com"))).isTrue();
        assertThat(GmailSyncService.stillExcluded(e, List.of("other"))).isFalse();
        ProcessedEmail old = ProcessedEmail.builder().type("EXCLUDED").resultSummary("Excluded").build();
        assertThat(GmailSyncService.stillExcluded(old, List.of("other"))).as("pattern not recorded").isTrue();
        assertThat(GmailSyncService.stillExcluded(old, List.of())).isFalse();
    }

    @Test
    @DisplayName("PAN-opened statements are recognised from subdomains too")
    void panPasswordSenders() {
        assertThat(PdfImportService.usesPanPassword("camsonline.co.in")).isTrue();
        assertThat(PdfImportService.usesPanPassword("mailers.camsonline.co.in")).isTrue();
        assertThat(PdfImportService.usesPanPassword("notcamsonline.co.in")).isFalse();
        assertThat(PdfImportService.usesPanPassword(null)).isFalse();
    }

    @Test
    @DisplayName("a contract note the model read goes to the fixed parser once, then not again")
    void rereadOnceByRules() {
        PendingPdf legacy = PendingPdf.builder().status("IMPORTED").providerKey("mstock.com").subject("Contract Note for 03-10-2026").build();
        assertThat(PdfImportService.dueRereadByRules(legacy)).as("readBy unrecorded").isTrue();
        legacy.setReadBy("gemini:gemini-2.5-flash");
        assertThat(PdfImportService.dueRereadByRules(legacy)).isTrue();
        legacy.setReadBy("gemini:gemini-2.5-flash" + PdfImportService.REREAD_SUFFIX);
        assertThat(PdfImportService.dueRereadByRules(legacy)).isFalse();
        legacy.setReadBy("rules");
        assertThat(PdfImportService.dueRereadByRules(legacy)).isFalse();
        PendingPdf other = PendingPdf.builder().status("IMPORTED").providerKey("zerodha.com").subject("Contract Note").build();
        assertThat(PdfImportService.dueRereadByRules(other)).as("no fixed parser").isFalse();
        PendingPdf locked = PendingPdf.builder().status("LOCKED").providerKey("mstock.com").subject("Contract Note").build();
        assertThat(PdfImportService.dueRereadByRules(locked)).isFalse();
    }

    @Test
    @DisplayName("issuer names compare by brand, not by kind of business")
    void brandStripsBusinessKind() {
        assertThat(EmailLLMParserService.brand("AXIS_BANK")).isEqualTo("axis");
        assertThat(EmailLLMParserService.brand("Axis Mutual Fund")).isEqualTo("axis");
        assertThat(EmailLLMParserService.brand("HDFC_AMC")).isEqualTo("hdfc");
        assertThat(EmailLLMParserService.brand("Nippon India MF")).isEqualTo("nippon");
    }

    @Test
    @DisplayName("a closing balance keeps its ISIN only when the statement prints a real one")
    void closingBalanceIsin() {
        String text = "Axis Bluechip Fund - Direct Growth ISIN: INF846K01DP8 Closing Unit Balance: 120.500";
        assertThat(EmailLLMParserService.statedIsin("inf846k01dp8", text)).isEqualTo("INF846K01DP8");
        assertThat(EmailLLMParserService.statedIsin("INF209K01YN0", text)).as("not in the statement").isNull();
        assertThat(EmailLLMParserService.statedIsin("Axis Bluechip", text)).isNull();
        assertThat(EmailLLMParserService.statedIsin(null, text)).isNull();
    }
}
