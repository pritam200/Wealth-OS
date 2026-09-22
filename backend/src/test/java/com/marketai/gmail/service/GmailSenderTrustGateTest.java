package com.marketai.gmail.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.gmail.dto.GmailSyncResult;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.parser.EmailParser;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Proves the sender-authority gate actually blocks on the live sync path.
 *
 * The evaluator itself is covered by SenderTrustEvaluatorTest; this test exists because the
 * wiring is the part that silently fails. A correct evaluator that is never consulted, or
 * consulted after the import has already happened, provides no protection at all.
 */
class GmailSenderTrustGateTest {

    private static final Long USER_ID = 7L;
    private static final String MSG_ID = "msg-abc";

    private GmailTokenRepository tokenRepo;
    private ProcessedEmailRepository processedRepo;
    private GmailClientService gmailClient;
    private UserRepository userRepo;
    private ParsedEmailImporter importer;
    private com.marketai.ai.review.service.EmailReviewService reviewService;
    private com.marketai.ai.intel.EmailIntelAgent intelAgent;
    private PendingPdfRepository pendingPdfRepo;
    private ExcludedSenderRepository excludedSenderRepo;

    private GmailSyncService service;

    /**
     * Stands in for any of the 18 real parsers: matches on a substring of the From header.
     *
     * <p>Matches an unregistered broker too, so the UNKNOWN_DOMAIN case is reachable at all. A
     * From containing "zerodha" can never be unknown — for an unverified domain the evaluator
     * searches the whole header, so "zerodha" anywhere in it is an impersonation claim by design.
     */
    private static class SubstringParser implements EmailParser {
        @Override public boolean canParse(String from, String subject) {
            if (from == null) return false;
            String f = from.toLowerCase();
            return f.contains("zerodha") || f.contains("smallbroker");
        }
        @Override public List<ParsedEmail> parse(String from, String subject, String body) {
            return List.of(ParsedEmail.builder()
                .type(ParsedEmail.Type.TRADE_BUY)
                .symbol("RELIANCE").quantity(25)
                .price(new BigDecimal("1412.50"))
                .amount(new BigDecimal("35332.50"))
                .tradeDate(LocalDate.of(2026, 9, 12))
                .build());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tokenRepo          = mock(GmailTokenRepository.class);
        processedRepo      = mock(ProcessedEmailRepository.class);
        gmailClient        = mock(GmailClientService.class);
        userRepo           = mock(UserRepository.class);
        importer           = mock(ParsedEmailImporter.class);
        reviewService      = mock(com.marketai.ai.review.service.EmailReviewService.class);
        intelAgent         = mock(com.marketai.ai.intel.EmailIntelAgent.class);
        pendingPdfRepo     = mock(PendingPdfRepository.class);
        excludedSenderRepo = mock(ExcludedSenderRepository.class);

        service = new GmailSyncService(
            tokenRepo, processedRepo, gmailClient, userRepo,
            List.<EmailParser>of(new SubstringParser()),
            new SenderTrustEvaluator(),
            new com.marketai.document.route.SelectionComparator(
                new com.marketai.document.classify.DocumentClassifier(List.of(
                    new com.marketai.document.classify.SenderDomainStage(),
                    new com.marketai.document.classify.SubjectPatternStage())),
                new com.marketai.document.route.ParserRouter()),
            importer,
            pendingPdfRepo, excludedSenderRepo,
            mock(PasswordHintExtractor.class), mock(PdfImportService.class),
            intelAgent, reviewService,
            mock(com.marketai.ai.llm.LlmProviderRouter.class));

        User user = new User();
        user.setId(USER_ID);
        GmailToken token = GmailToken.builder()
            .user(user).accessToken("a").refreshToken("r").build();
        when(tokenRepo.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(excludedSenderRepo.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(processedRepo.findByUserIdAndGmailMessageId(eq(USER_ID), anyString()))
            .thenReturn(Optional.empty());
        when(processedRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Gmail gmail = mock(Gmail.class);
        when(gmailClient.buildGmailService(anyString(), anyString(), any())).thenReturn(gmail);
        when(gmailClient.buildGmailService(anyString(), anyString())).thenReturn(gmail);
        when(gmailClient.getMessage(any(), anyString())).thenReturn(new Message().setId(MSG_ID));
        when(gmailClient.getSubject(any())).thenReturn("Contract Note cum Tax Invoice");
        when(gmailClient.getBodyText(any())).thenReturn("Symbol: RELIANCE Qty: 25 Rate: 1,412.50");
        when(gmailClient.findPdfAttachments(any())).thenReturn(List.of());
        // Keep the LLM fallback out of the picture — this test is about the trust gate.
        when(intelAgent.isEnabled()).thenReturn(false);
    }

    @Test
    @DisplayName("a spoofed display name is routed to review and never imported")
    void spoofedSenderIsBlockedFromImport() throws Exception {
        when(gmailClient.getFrom(any())).thenReturn("\"Zerodha Alerts\" <noreply@attacker.example>");

        GmailSyncResult result = service.syncSpecificMessages(USER_ID, List.of(MSG_ID));

        // The parser matched — substring routing is what makes this reachable at all.
        // The gate is what stops the extracted trade from becoming a ledger row.
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(reviewService).enqueue(eq(USER_ID), eq(MSG_ID),
            contains("attacker.example"), anyString(), any());
        assertThat(result.getImported()).isZero();
    }

    @Test
    @DisplayName("a genuine issuer domain still imports — the gate is not a blanket block")
    void genuineSenderStillImports() throws Exception {
        when(gmailClient.getFrom(any())).thenReturn("\"Zerodha\" <noreply@zerodha.com>");

        service.syncSpecificMessages(USER_ID, List.of(MSG_ID));

        verify(importer).importParsedEmail(eq(USER_ID), any(), any(ParsedEmail.class), eq(MSG_ID), any());
        verify(reviewService, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unregistered but honest sender imports rather than being held back")
    void unknownDomainIsNotBlocked() throws Exception {
        // The registry will always lag reality. Blocking unrecognised domains would mean any
        // bank we have not catalogued silently stops importing, which costs more than it saves.
        //
        // The domain must genuinely be outside the registry for this to test anything — it used
        // to reuse the registered zerodha.com, making it a duplicate of genuineSenderStillImports
        // and leaving the UNKNOWN_DOMAIN path completely uncovered. The display name claims no
        // issuer, so this is "unknown", not "spoofed".
        when(gmailClient.getFrom(any())).thenReturn("\"Trade Alerts\" <noreply@smallbroker.example>");

        service.syncSpecificMessages(USER_ID, List.of(MSG_ID));

        verify(importer).importParsedEmail(any(), any(), any(), any(), any());
        verify(reviewService, never()).enqueue(any(), any(), any(), any(), any());
    }
}
