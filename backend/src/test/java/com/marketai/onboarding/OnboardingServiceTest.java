package com.marketai.onboarding;

import com.marketai.dataplatform.service.CsvImportService;
import com.marketai.gmail.repository.GmailTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnboardingServiceTest {

    private static final long USER = 7L;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private ImportSourceRepository repo;
    private CsvImportService csv;
    private OnboardingService service;
    private com.marketai.cas.CasImportService cas;
    private com.marketai.identity.service.FinancialIdentityService identity;

    @BeforeEach
    void setUp() {
        repo = mock(ImportSourceRepository.class);
        csv = mock(CsvImportService.class);
        GmailTokenRepository gmail = mock(GmailTokenRepository.class);
        when(gmail.findByUserId(USER)).thenReturn(Optional.empty());
        when(repo.save(any(ImportSource.class))).thenAnswer(i -> i.getArgument(0));
        cas = mock(com.marketai.cas.CasImportService.class);
        identity = mock(com.marketai.identity.service.FinancialIdentityService.class);
        service = new OnboardingService(repo, csv, gmail, cas, identity);
    }

    private ImportSource source(SourceKind kind, LocalDate through) {
        return ImportSource.builder().id(1L).userId(USER).kind(kind).name("Zerodha").syncedThrough(through).build();
    }

    @Test
    void statusIsNotStartedCurrentOrStaleByRefreshWindow() {
        when(repo.findByUserIdOrderByKindAscNameAsc(USER)).thenReturn(List.of(
            source(SourceKind.STOCKS, null),
            source(SourceKind.STOCKS, TODAY.minusDays(10)),
            source(SourceKind.STOCKS, TODAY.minusDays(36))));
        var views = service.checklist(USER).sources();
        assertThat(views).extracting("status").containsExactly("NOT_STARTED", "CURRENT", "STALE");
        assertThat(views.get(2).daysBehind()).isEqualTo(36L);
    }

    @Test
    void importMovesWatermarkForwardOnly() throws Exception {
        ImportSource s = source(SourceKind.MUTUAL_FUNDS, TODAY.minusDays(5));
        when(repo.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(s));
        when(csv.importCsv(eq(USER), any(), any())).thenReturn(
            new CsvImportService.Summary(3, 3, 0, 0, List.of(), TODAY.minusDays(90), TODAY.minusDays(30)));

        service.importFile(USER, 1L, new MockMultipartFile("file", "old.csv", "text/csv", "a,b\n1,2".getBytes()), false);
        assertThat(s.getSyncedThrough()).isEqualTo(TODAY.minusDays(5)); // an older file doesn't move it back

        when(csv.importCsv(eq(USER), any(), any())).thenReturn(
            new CsvImportService.Summary(2, 2, 0, 0, List.of(), TODAY.minusDays(4), TODAY.minusDays(1)));
        service.importFile(USER, 1L, new MockMultipartFile("file", "new.csv", "text/csv", "a,b\n1,2".getBytes()), false);
        assertThat(s.getSyncedThrough()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void creditCardsCannotImportFilesAndFutureDatesAreRejected() {
        ImportSource card = source(SourceKind.CREDIT_CARDS, null);
        when(repo.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(card));
        assertThatThrownBy(() -> service.importFile(USER, 1L,
            new MockMultipartFile("file", "s.csv", "text/csv", "x".getBytes()), false))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.setSyncedThrough(USER, 1L, TODAY.plusDays(1)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anotherUsersSourceIsNotFound() {
        when(repo.findByIdAndUserId(1L, 99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.remove(99L, 1L)).isInstanceOf(ResponseStatusException.class);
    }

    private static com.marketai.cas.CasImportService.Summary casSummary(com.marketai.cas.CasImportService.Kind kind) {
        return new com.marketai.cas.CasImportService.Summary(null, TODAY.minusDays(2), 3, 10, 10, 0, 0, List.of(), List.of(), kind);
    }

    @Test
    void oneBoxUploadOpensALockedPdfWithAPasswordWorkedOutFromThePan() throws Exception {
        var pdf = new MockMultipartFile("file", "cas.pdf", "application/pdf", "x".getBytes());
        when(repo.findByUserIdOrderByKindAscNameAsc(USER)).thenReturn(List.of());
        when(cas.needsPassword(any())).thenReturn(true);
        when(identity.derivePassword(eq(USER), eq(com.marketai.identity.service.PasswordStrategy.PAN_UPPERCASE))).thenReturn(Optional.of("ABCDE1234F"));
        when(identity.derivePassword(eq(USER), argThat(st -> st != com.marketai.identity.service.PasswordStrategy.PAN_UPPERCASE))).thenReturn(Optional.empty());
        when(cas.importPdf(eq(USER), any(), eq("ABCDE1234F"), any(), isNull()))
            .thenReturn(casSummary(com.marketai.cas.CasImportService.Kind.DEMAT));

        var r = service.importCasAuto(USER, pdf, null);

        assertThat(r.source().kind()).isEqualTo("STOCKS");   // kind read from the document, source created
        assertThat(r.source().syncedThrough()).isEqualTo(TODAY.minusDays(2));
    }

    @Test
    void oneBoxUploadMovesOnToTheNextGuessWhenAPasswordIsWrongButStopsOnARealError() throws Exception {
        var pdf = new MockMultipartFile("file", "cas.pdf", "application/pdf", "x".getBytes());
        when(repo.findByUserIdOrderByKindAscNameAsc(USER)).thenReturn(List.of());
        when(cas.needsPassword(any())).thenReturn(true);
        when(identity.derivePassword(eq(USER), any())).thenReturn(Optional.empty());
        when(identity.derivePassword(eq(USER), eq(com.marketai.identity.service.PasswordStrategy.PAN_UPPERCASE))).thenReturn(Optional.of("WRONG"));
        when(cas.importPdf(eq(USER), any(), eq("RIGHT"), any(), isNull()))
            .thenReturn(casSummary(com.marketai.cas.CasImportService.Kind.MUTUAL_FUND));
        when(cas.importPdf(eq(USER), any(), eq("WRONG"), any(), isNull()))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "That password didn't open the PDF."));

        var r = service.importCasAuto(USER, pdf, "RIGHT");   // typed password is tried first
        assertThat(r.source().kind()).isEqualTo("MUTUAL_FUNDS");
        verify(cas, never()).importPdf(eq(USER), any(), eq("WRONG"), any(), any());

        reset(cas);
        when(cas.needsPassword(any())).thenReturn(true);
        when(cas.importPdf(eq(USER), any(), eq("WRONG"), any(), isNull()))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "That password didn't open the PDF."));
        assertThatThrownBy(() -> service.importCasAuto(USER, pdf, null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("none of the passwords");
    }

    @Test
    void oneBoxUploadRejectsNonPdfWithAPointerToTheRightSection() {
        assertThatThrownBy(() -> service.importCasAuto(USER, new MockMultipartFile("file", "s.csv", "text/csv", "x".getBytes()), null))
            .hasMessageContaining("PDF");
    }
}
