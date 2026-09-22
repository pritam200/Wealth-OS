package com.marketai.card;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.RewardTransaction;
import com.marketai.card.entity.RewardTransactionType;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.repository.RewardTransactionRepository;
import com.marketai.card.service.RewardLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The ledger is the point: a card's points balance must be the sum of its earn/redeem/adjust
 * history once that history exists, never a value someone can silently overwrite.
 */
class RewardLedgerServiceTest {

    private CreditCardRepository cardRepository;
    private RewardTransactionRepository ledgerRepository;
    private RewardLedgerService ledger;

    @BeforeEach
    void setup() {
        cardRepository = mock(CreditCardRepository.class);
        ledgerRepository = mock(RewardTransactionRepository.class);
        ledger = new RewardLedgerService(cardRepository, ledgerRepository);
    }

    private CreditCard card(Long id, Long userId, Integer storedBalance) {
        return CreditCard.builder().id(id).userId(userId).name("Test Card")
            .pointsBalance(storedBalance).build();
    }

    @Test
    void balanceFallsBackToStoredValueWhenNoLedgerHistoryExists() {
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);
        assertThat(ledger.getBalance(5L, 1200)).isEqualTo(1200);
    }

    @Test
    void balanceIsTheLedgerSumOnceHistoryExists() {
        when(ledgerRepository.existsByCardId(5L)).thenReturn(true);
        when(ledgerRepository.sumPointsByCardId(5L)).thenReturn(750);
        // Stored value is ignored entirely once real history exists — it must never win.
        assertThat(ledger.getBalance(5L, 999999)).isEqualTo(750);
    }

    @Test
    void earnRecordsAPositiveEntryAgainstAnOwnedCard() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 0)));
        ledger.earn(1L, 5L, 500, new BigDecimal("125.00"), LocalDate.of(2026, 1, 15), "Statement bonus");

        ArgumentCaptor<RewardTransaction> captor = ArgumentCaptor.forClass(RewardTransaction.class);
        verify(ledgerRepository).save(captor.capture());
        RewardTransaction saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(RewardTransactionType.EARN);
        assertThat(saved.getPoints()).isEqualTo(500);
        assertThat(saved.getCardId()).isEqualTo(5L);
        assertThat(saved.getUserId()).isEqualTo(1L);
    }

    @Test
    void earnRejectsNonPositivePoints() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 0)));
        assertThatThrownBy(() -> ledger.earn(1L, 5L, 0, null, null, null))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ledger.earn(1L, 5L, -10, null, null, null))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void redeemRecordsANegativeEntryWhenBalanceCovers() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 1000)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.redeem(1L, 5L, 400, new BigDecimal("100.00"), LocalDate.of(2026, 2, 1), "Flight booking");

        // Two rows: the legacy 1,000 is materialised as an opening entry first (see
        // legacyStoredBalanceSurvivesTheFirstLedgerEntry), then the redemption itself.
        ArgumentCaptor<RewardTransaction> captor = ArgumentCaptor.forClass(RewardTransaction.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        RewardTransaction saved = captor.getAllValues().get(1);
        assertThat(saved.getType()).isEqualTo(RewardTransactionType.REDEEM);
        assertThat(saved.getPoints()).isEqualTo(-400);
    }

    @Test
    void redeemRejectsRedeemingMoreThanTheBalance() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 300)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        assertThatThrownBy(() -> ledger.redeem(1L, 5L, 400, null, null, null))
            .isInstanceOf(ResponseStatusException.class);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void redeemAccountsForPriorLedgerHistoryNotJustTheStoredValue() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 0)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(true);
        when(ledgerRepository.sumPointsByCardId(5L)).thenReturn(500);

        // Stored balance is 0, but the real ledger balance is 500 — redemption must use the latter.
        ledger.redeem(1L, 5L, 500, null, null, null);
        verify(ledgerRepository).save(any());
    }

    @Test
    void adjustToAbsoluteRecordsOnlyTheDelta() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 1000)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.adjustToAbsolute(1L, 5L, 1500, "Manual correction");

        ArgumentCaptor<RewardTransaction> captor = ArgumentCaptor.forClass(RewardTransaction.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        // Opening row carries the legacy 1,000; the correction itself records only the +500 delta,
        // so the ledger replays to exactly the 1,500 the user typed.
        assertThat(captor.getAllValues().get(0).getPoints()).isEqualTo(1000);
        assertThat(captor.getAllValues().get(1).getType()).isEqualTo(RewardTransactionType.ADJUST);
        assertThat(captor.getAllValues().get(1).getPoints()).isEqualTo(500);
        assertThat(captor.getAllValues().stream().mapToInt(RewardTransaction::getPoints).sum()).isEqualTo(1500);
    }

    @Test
    void legacyStoredBalanceSurvivesTheFirstLedgerEntry() {
        // A card added before the ledger existed: 1,000 points stored, no history. Redeeming 300
        // must leave 700, not −300 — the stored figure has to become a real ledger row first.
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 1000)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.redeem(1L, 5L, 300, null, null, null);

        ArgumentCaptor<RewardTransaction> captor = ArgumentCaptor.forClass(RewardTransaction.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        RewardTransaction opening = captor.getAllValues().get(0);
        assertThat(opening.getType()).isEqualTo(RewardTransactionType.ADJUST);
        assertThat(opening.getPoints()).isEqualTo(1000);
        assertThat(opening.getNote()).contains("Opening balance");
        assertThat(captor.getAllValues().stream().mapToInt(RewardTransaction::getPoints).sum()).isEqualTo(700);
    }

    @Test
    void firstEarnOnALegacyCardDoesNotDiscardTheStoredBalance() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 2500)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.earn(1L, 5L, 500, null, null, "Statement bonus");

        ArgumentCaptor<RewardTransaction> captor = ArgumentCaptor.forClass(RewardTransaction.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().stream().mapToInt(RewardTransaction::getPoints).sum()).isEqualTo(3000);
    }

    @Test
    void noOpeningRowIsWrittenWhenThereIsNothingToCarryOver() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 0)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.earn(1L, 5L, 500, null, null, null);
        verify(ledgerRepository, times(1)).save(any());
    }

    @Test
    void openingRowIsWrittenOnlyOnceBecauseHistoryThenExists() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 1000)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(true);
        when(ledgerRepository.sumPointsByCardId(5L)).thenReturn(1000);

        ledger.earn(1L, 5L, 500, null, null, null);
        verify(ledgerRepository, times(1)).save(any());
    }

    @Test
    void adjustToAbsoluteIsANoOpWhenTheValueAlreadyMatches() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 1000)));
        when(ledgerRepository.existsByCardId(5L)).thenReturn(false);

        ledger.adjustToAbsolute(1L, 5L, 1000, "No-op");
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void operationsOnACardOwnedBySomeoneElseAreRejected() {
        when(cardRepository.findByIdAndUserId(5L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ledger.earn(2L, 5L, 100, null, null, null))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void historyIsOrderedNewestFirstAndScopedToTheOwningUser() {
        when(cardRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(card(5L, 1L, 0)));
        List<RewardTransaction> rows = List.of(
            RewardTransaction.builder().id(2L).cardId(5L).userId(1L).type(RewardTransactionType.EARN)
                .points(500).transactionDate(LocalDate.of(2026, 2, 1)).build(),
            RewardTransaction.builder().id(1L).cardId(5L).userId(1L).type(RewardTransactionType.EARN)
                .points(300).transactionDate(LocalDate.of(2026, 1, 1)).build());
        when(ledgerRepository.findByCardIdOrderByTransactionDateDescCreatedAtDesc(5L)).thenReturn(rows);

        assertThat(ledger.history(1L, 5L)).hasSize(2).first().extracting(RewardTransaction::getId).isEqualTo(2L);
    }
}
