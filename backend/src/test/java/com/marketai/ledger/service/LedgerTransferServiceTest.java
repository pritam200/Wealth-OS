package com.marketai.ledger.service;

import com.marketai.auth.entity.User;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.investmentplan.service.PlannedInvestmentMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The net-worth invariant: a transfer moves money between things the user already owns, so
 * total assets must be identical before and after.
 */
class LedgerTransferServiceTest {

    private static final Long USER_ID = 42L;

    private LedgerTransferRepository transferRepo;
    private CashAccountRepository accountRepo;
    private LedgerTransferService service;
    private User user;

    private CashAccount bank;
    private CashAccount wallet;

    @BeforeEach
    void setup() {
        transferRepo = mock(LedgerTransferRepository.class);
        accountRepo = mock(CashAccountRepository.class);
        service = new LedgerTransferService(transferRepo, accountRepo, mock(PlannedInvestmentMatcher.class));

        user = new User();
        user.setId(USER_ID);

        bank = CashAccount.builder().id(1L).user(user).name("HDFC Savings")
            .balance(new BigDecimal("100000.00")).active(true).build();
        wallet = CashAccount.builder().id(2L).user(user).name("Cash in hand")
            .balance(new BigDecimal("5000.00")).active(true).build();

        when(accountRepo.findByIdAndUser_Id(1L, USER_ID)).thenReturn(Optional.of(bank));
        when(accountRepo.findByIdAndUser_Id(2L, USER_ID)).thenReturn(Optional.of(wallet));
        when(accountRepo.save(any(CashAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(transferRepo.save(any(LedgerTransfer.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void accountToAccountTransfer_leavesTotalCashUnchanged() {
        BigDecimal before = bank.getBalance().add(wallet.getBalance());

        service.record(user, 1L, 2L, "CASH_ACCOUNT", null,
            new BigDecimal("20000"), LocalDate.now(), "Moved to wallet", null);

        assertThat(bank.getBalance()).isEqualByComparingTo("80000.00");
        assertThat(wallet.getBalance()).isEqualByComparingTo("25000.00");
        // The whole point: allocation changed, total did not.
        assertThat(bank.getBalance().add(wallet.getBalance())).isEqualByComparingTo(before);
    }

    @Test
    void bankToMutualFundTransfer_debitsCashOnly_soNetWorthIsUnchangedOnceTheFundIsBooked() {
        // Bank -> MF: cash drops by 50k here, and the MF holding rises by 50k through its own
        // import path. Net worth therefore nets to zero. Before cash was tracked, only the MF
        // side existed and net worth grew by the full transfer amount.
        service.record(user, 1L, null, "MUTUAL_FUND", "Parag Parikh Flexi Cap",
            new BigDecimal("50000"), LocalDate.now(), "Lumpsum", null);

        assertThat(bank.getBalance()).isEqualByComparingTo("50000.00");
        // No Income or Expense row is created by a transfer — verified structurally: this
        // service has no income/expense collaborator at all.
        verify(accountRepo).save(bank);
    }

    @Test
    void transferIsNeverAppliedTwice() {
        LedgerTransfer t = service.record(user, 1L, 2L, "CASH_ACCOUNT", null,
            new BigDecimal("10000"), LocalDate.now(), null, null);

        assertThat(t.getApplied()).isTrue();
        assertThat(bank.getBalance()).isEqualByComparingTo("90000.00");
        // Only one debit and one credit occurred for this transfer.
        verify(accountRepo, times(1)).save(bank);
        verify(accountRepo, times(1)).save(wallet);
    }

    @Test
    void deletingATransferRestoresBothBalancesExactly() {
        LedgerTransfer t = service.record(user, 1L, 2L, "CASH_ACCOUNT", null,
            new BigDecimal("15000"), LocalDate.now(), null, null);
        when(transferRepo.findByIdAndUser_Id(any(), eq(USER_ID))).thenReturn(Optional.of(t));
        t.setId(99L);

        service.delete(user, 99L);

        assertThat(bank.getBalance()).isEqualByComparingTo("100000.00");
        assertThat(wallet.getBalance()).isEqualByComparingTo("5000.00");
        verify(transferRepo).delete(t);
    }

    @Test
    void rejectsNonPositiveAmountsAndSelfTransfers() {
        assertThatThrownBy(() -> service.record(user, 1L, 2L, "CASH_ACCOUNT", null,
            BigDecimal.ZERO, LocalDate.now(), null, null))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.record(user, 1L, 2L, "CASH_ACCOUNT", null,
            new BigDecimal("-500"), LocalDate.now(), null, null))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.record(user, 1L, 1L, "CASH_ACCOUNT", null,
            new BigDecimal("500"), LocalDate.now(), null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("same account");
    }

    @Test
    void requiresADestinationType() {
        assertThatThrownBy(() -> service.record(user, 1L, null, null, null,
            new BigDecimal("500"), LocalDate.now(), null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("destinationType");
    }
}
