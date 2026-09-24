package com.marketai.gmail.service;

import com.google.api.services.gmail.Gmail;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.SavedPdfPassword;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.SavedPdfPasswordRepository;
import com.marketai.gmail.security.PasswordCipher;
import com.marketai.identity.service.FinancialIdentityService;
import com.marketai.document.classify.DocTypes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A saved PDF password used to be keyed only on sender domain ({@code providerKey}), so
 * learning the password for one statement kind from an institution (e.g. a card statement)
 * silently overwrote — and then got auto-applied to — a different statement kind from the
 * same sender (e.g. a bank statement), even though many issuers use a different password
 * convention per document type. {@link SavedPdfPassword} and {@link PendingPdf} now both carry
 * a {@code documentType}, and {@link PdfImportService#unlock} only treats two pending PDFs as
 * "the same case" when both the provider and the document type match.
 */
class PdfPasswordLearningDocumentTypeTest {

    private static final Long USER_ID = 9L;
    private static final String PROVIDER = "hdfcbank.com";

    private PendingPdfRepository pendingPdfRepo;
    private SavedPdfPasswordRepository savedPasswordRepo;
    private GmailTokenRepository tokenRepo;
    private UserRepository userRepo;
    private GmailClientService gmailClient;
    private PasswordCipher passwordCipher;
    private EmailLLMParserService emailLlmParserService;
    private PdfImportService service;
    private byte[] plainPdfBytes;

    @BeforeEach
    void setUp() throws Exception {
        pendingPdfRepo = mock(PendingPdfRepository.class);
        savedPasswordRepo = mock(SavedPdfPasswordRepository.class);
        tokenRepo = mock(GmailTokenRepository.class);
        userRepo = mock(UserRepository.class);
        gmailClient = mock(GmailClientService.class);
        passwordCipher = mock(PasswordCipher.class);
        emailLlmParserService = mock(EmailLLMParserService.class);
        when(emailLlmParserService.process(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(EmailLLMParserService.Result.builder()
                .outcome(EmailLLMParserService.Outcome.NOT_FINANCIAL).build());

        service = new PdfImportService(
            pendingPdfRepo,
            mock(FinancialIdentityService.class),
            savedPasswordRepo, tokenRepo, userRepo, gmailClient,
            passwordCipher,
            emailLlmParserService);

        // An unencrypted PDF opens with PDFBox regardless of the password supplied — good
        // enough to exercise the unlock/save-password/sibling-sweep logic without needing a
        // real encrypted fixture.
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            plainPdfBytes = out.toByteArray();
        }

        User user = new User();
        user.setId(USER_ID);
        GmailToken token = GmailToken.builder().user(user).accessToken("a").refreshToken("r").build();
        when(tokenRepo.findByUserId(USER_ID)).thenReturn(Optional.of(token));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(gmailClient.buildGmailService(anyString(), anyString())).thenReturn(mock(Gmail.class));
        when(gmailClient.downloadAttachment(any(), anyString(), anyString())).thenReturn(plainPdfBytes);
        when(passwordCipher.encrypt(anyString())).thenReturn("encrypted");
        when(pendingPdfRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private PendingPdf pdf(Long id, String documentType) {
        return PendingPdf.builder()
            .id(id).userId(USER_ID).gmailMessageId("msg-" + id).attachmentId("att-" + id)
            .filename("statement.pdf").sender("alerts@hdfcbank.com").subject("Statement")
            .providerKey(PROVIDER).documentType(documentType)
            .status("NEEDS_PASSWORD")
            .build();
    }

    @Test
    @DisplayName("unlocking a card statement saves the password keyed by (provider, CARD_STATEMENT), not provider alone")
    void savedPasswordIsKeyedByDocumentType() throws Exception {
        PendingPdf cardStatement = pdf(1L, DocTypes.CARD_STATEMENT);
        when(pendingPdfRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(cardStatement));
        when(pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList()))
            .thenReturn(List.of());
        when(savedPasswordRepo.findByUserIdAndProviderKeyAndDocumentType(USER_ID, PROVIDER, DocTypes.CARD_STATEMENT))
            .thenReturn(Optional.empty());

        service.unlock(USER_ID, 1L, "somepassword", true);

        verify(savedPasswordRepo).save(argThat(saved ->
            PROVIDER.equals(saved.getProviderKey()) && DocTypes.CARD_STATEMENT.equals(saved.getDocumentType())));
    }

    @Test
    @DisplayName("a newly-learned card-statement password is not auto-applied to a queued bank-statement PDF from the same sender")
    void siblingSweepDoesNotCrossDocumentTypes() throws Exception {
        PendingPdf cardStatement = pdf(1L, DocTypes.CARD_STATEMENT);
        PendingPdf bankStatement = pdf(2L, DocTypes.BANK_STATEMENT);
        when(pendingPdfRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(cardStatement));
        when(pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList()))
            .thenReturn(List.of(bankStatement));
        when(savedPasswordRepo.findByUserIdAndProviderKeyAndDocumentType(eq(USER_ID), eq(PROVIDER), any()))
            .thenReturn(Optional.empty());

        service.unlock(USER_ID, 1L, "somepassword", true);

        // The bank-statement sibling must never have attemptUnlock run against it with the
        // card-statement password — verified indirectly: it is never re-fetched/downloaded.
        verify(gmailClient, never()).downloadAttachment(any(), eq("msg-2"), anyString());
        assertThat(bankStatement.getStatus()).isEqualTo("NEEDS_PASSWORD");
    }

    @Test
    @DisplayName("a same-provider, same-document-type sibling still gets auto-unlocked")
    void siblingSweepStillAppliesWithinSameDocumentType() throws Exception {
        PendingPdf cardStatement1 = pdf(1L, DocTypes.CARD_STATEMENT);
        PendingPdf cardStatement2 = pdf(2L, DocTypes.CARD_STATEMENT);
        when(pendingPdfRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(cardStatement1));
        when(pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), anyList()))
            .thenReturn(List.of(cardStatement2));
        when(savedPasswordRepo.findByUserIdAndProviderKeyAndDocumentType(eq(USER_ID), eq(PROVIDER), any()))
            .thenReturn(Optional.empty());

        service.unlock(USER_ID, 1L, "somepassword", true);

        verify(gmailClient).downloadAttachment(any(), eq("msg-2"), anyString());
    }
}
