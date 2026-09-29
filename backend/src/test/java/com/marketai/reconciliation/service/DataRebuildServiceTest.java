package com.marketai.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.reconciliation.entity.DataBackup;
import com.marketai.reconciliation.repository.DataBackupRepository;
import com.marketai.reconciliation.service.DataAuditService.Classification;
import com.marketai.reconciliation.service.DataAuditService.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataRebuildServiceTest {

    private static final Long USER = 1L;
    private DataBackupRepository backups;
    private DataAuditService audit;
    private ExpenseRepository expenses;
    private IncomeRepository incomes;
    private PortfolioService portfolio;
    private DataRebuildService service;
    private DataBackup backup;

    @BeforeEach
    void setUp() {
        backups = mock(DataBackupRepository.class);
        audit = mock(DataAuditService.class);
        expenses = mock(ExpenseRepository.class);
        incomes = mock(IncomeRepository.class);
        portfolio = mock(PortfolioService.class);
        service = new DataRebuildService(backups, audit, expenses, incomes, mock(TransactionRepository.class),
            portfolio, mock(JdbcTemplate.class), new ObjectMapper());
        backup = DataBackup.builder().id(5L).userId(USER).createdAt(LocalDateTime.now().minusHours(1)).payload("{}").build();
        when(backups.findByIdAndUserId(5L, USER)).thenReturn(Optional.of(backup));
        when(audit.audit(USER)).thenReturn(DataAuditService.Report.builder().findings(List.of(
            DataAuditService.Finding.builder().entity(Entity.EXPENSE).id(19L).duplicateOf(18L).classification(Classification.DUPLICATE).build(),
            DataAuditService.Finding.builder().entity(Entity.EXPENSE).id(22L).classification(Classification.REQUIRES_RECONCILIATION).build()
        )).build());
    }

    private static DataRebuildService.RebuildRequest req(String confirm, Long backupId, DataRebuildService.Removal... r) {
        DataRebuildService.RebuildRequest q = new DataRebuildService.RebuildRequest();
        q.setConfirm(confirm);
        q.setBackupId(backupId);
        q.setRemove(List.of(r));
        return q;
    }

    @Test
    @DisplayName("no confirmation or no backup: refused, nothing touched")
    void needsConfirmationAndBackup() {
        assertThatThrownBy(() -> service.rebuild(USER, req("yes", 5L))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.rebuild(USER, req("REBUILD", null))).isInstanceOf(ResponseStatusException.class);
        backup.setCreatedAt(LocalDateTime.now().minusDays(2));
        assertThatThrownBy(() -> service.rebuild(USER, req("REBUILD", 5L))).hasMessageContaining("hours old");
        verifyNoInteractions(expenses, incomes, portfolio);
    }

    @Test
    @DisplayName("naming anything the audit doesn't call a duplicate fails the whole request")
    void onlyDuplicates() {
        assertThatThrownBy(() -> service.rebuild(USER, req("REBUILD", 5L,
            new DataRebuildService.Removal(Entity.EXPENSE, 19L), new DataRebuildService.Removal(Entity.EXPENSE, 22L))))
            .hasMessageContaining("EXPENSE 22 is not a duplicate");
        verify(expenses, never()).deleteById(any());
        verify(portfolio, never()).rebuildHoldingsFromTransactions(any());
    }

    @Test
    @DisplayName("a confirmed rebuild removes the named duplicate, re-links its refunds, re-derives holdings, and uses up the backup")
    void appliesOnce() {
        Expense refund = Expense.builder().id(40L).refundOfExpenseId(19L).build();
        when(expenses.findByRefundOfExpenseId(19L)).thenReturn(List.of(refund));
        when(portfolio.rebuildHoldingsFromTransactions(USER)).thenReturn(2);

        var result = service.rebuild(USER, req("REBUILD", 5L, new DataRebuildService.Removal(Entity.EXPENSE, 19L)));

        verify(expenses).deleteById(19L);
        assertThat(refund.getRefundOfExpenseId()).isEqualTo(18L);
        assertThat(result.getHoldingsRederived()).isEqualTo(2);
        assertThat(backup.getAppliedAt()).isNotNull();
        assertThatThrownBy(() -> service.rebuild(USER, req("REBUILD", 5L))).hasMessageContaining("already used");
    }
}
