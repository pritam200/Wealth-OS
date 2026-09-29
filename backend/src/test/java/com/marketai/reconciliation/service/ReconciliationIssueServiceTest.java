package com.marketai.reconciliation.service;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import com.marketai.reconciliation.repository.ReconciliationIssueRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reconciliation findings are kept until they actually go away. */
class ReconciliationIssueServiceTest {

    private final List<ReconciliationIssueRecord> stored = new ArrayList<>();
    private ReconciliationService checks;
    private ReconciliationIssueService service;

    @BeforeEach
    void setUp() {
        checks = mock(ReconciliationService.class);
        ReconciliationIssueRecordRepository repo = mock(ReconciliationIssueRecordRepository.class);
        when(repo.findByUserId(1L)).thenAnswer(i -> new ArrayList<>(stored));
        when(repo.save(any())).thenAnswer(i -> {
            ReconciliationIssueRecord r = i.getArgument(0);
            if (!stored.contains(r)) stored.add(r);
            return r;
        });
        when(repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(eq(1L), anyList()))
            .thenAnswer(i -> stored.stream().filter(r -> i.<List<String>>getArgument(1).contains(r.getStatus())).toList());
        service = new ReconciliationIssueService(checks, repo);
    }

    private void found(ReconciliationIssue... issues) {
        when(checks.checkAll(1L)).thenReturn(ReconciliationReportDto.builder()
            .issueCount(issues.length).issues(List.of(issues)).build());
    }

    private static ReconciliationIssue backlog(int n) {
        return ReconciliationIssue.builder().domain("INGESTION").type("INGESTION_REVIEW_BACKLOG").severity("MEDIUM")
            .description(n + " review item(s) have been pending for more than 14 days").build();
    }

    @Test
    @DisplayName("an issue opens when found and resolves by itself once the check stops finding it")
    void opensAndResolves() {
        found(backlog(3));
        assertThat(service.refresh(1L)).singleElement().extracting(ReconciliationIssueRecord::getStatus).isEqualTo("OPEN");

        found();
        assertThat(service.refresh(1L)).isEmpty();
        assertThat(stored).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo("RESOLVED");
            assertThat(r.getResolvedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("the same finding with a changed count is the same issue, not a new one")
    void countChangeKeepsIdentity() {
        found(backlog(3));
        service.refresh(1L);
        found(backlog(5));
        service.refresh(1L);

        assertThat(stored).singleElement().satisfies(r -> assertThat(r.getDescription()).startsWith("5 "));
    }

    @Test
    @DisplayName("an acknowledged issue stays acknowledged while it persists, and a resolved one reopens if it returns")
    void acknowledgeAndReopen() {
        found(backlog(3));
        service.refresh(1L);
        ReconciliationIssueRecord r = stored.get(0);
        r.setStatus("ACKNOWLEDGED");

        service.refresh(1L);
        assertThat(r.getStatus()).isEqualTo("ACKNOWLEDGED");

        found();
        service.refresh(1L);
        found(backlog(2));
        service.refresh(1L);
        assertThat(r.getStatus()).isEqualTo("OPEN");
    }
}
