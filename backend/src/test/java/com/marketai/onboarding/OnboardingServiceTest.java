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

    @BeforeEach
    void setUp() {
        repo = mock(ImportSourceRepository.class);
        csv = mock(CsvImportService.class);
        GmailTokenRepository gmail = mock(GmailTokenRepository.class);
        when(gmail.findByUserId(USER)).thenReturn(Optional.empty());
        when(repo.save(any(ImportSource.class))).thenAnswer(i -> i.getArgument(0));
        service = new OnboardingService(repo, csv, gmail, mock(com.marketai.cas.CasImportService.class));
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
}
