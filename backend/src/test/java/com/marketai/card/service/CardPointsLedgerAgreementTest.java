package com.marketai.card.service;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.RewardTransaction;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.repository.RewardTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cached {@code CreditCard.pointsBalance} and a replay of the reward ledger must agree.
 *
 * <p>Two writers touch a card's points — the cached column and the ledger — which is exactly the
 * divergence {@code HoldingLedgerInvariantTest} exists to prevent for holdings. It was reachable
 * here: for a legacy card with a stored balance and no ledger rows, {@code updatePoints(5000)}
 * wrote only the +4,000 delta and set the column to 5,000, after which replaying the ledger gave
 * 4,000. This test runs both writers against an in-memory ledger and compares the two numbers.
 */
class CardPointsLedgerAgreementTest {

    private static final Long USER = 2L;
    private static final Long CARD = 88L;

    private final List<RewardTransaction> ledgerRows = new ArrayList<>();
    private CreditCard card;
    private CardService cardService;

    @BeforeEach
    void setUp() {
        CreditCardRepository cardRepo = mock(CreditCardRepository.class);
        RewardTransactionRepository ledgerRepo = mock(RewardTransactionRepository.class);

        when(cardRepo.findByIdAndUserId(eqCard(), eqUser())).thenAnswer(i -> Optional.of(card));
        when(cardRepo.save(any(CreditCard.class))).thenAnswer(i -> i.getArgument(0));

        // A real, if minimal, ledger: rows accumulate and the sum is computed from them.
        when(ledgerRepo.save(any(RewardTransaction.class))).thenAnswer(i -> {
            RewardTransaction t = i.getArgument(0);
            ledgerRows.add(t);
            return t;
        });
        when(ledgerRepo.existsByCardId(anyLong())).thenAnswer(i -> !ledgerRows.isEmpty());
        when(ledgerRepo.sumPointsByCardId(anyLong()))
            .thenAnswer(i -> ledgerRows.stream().mapToInt(RewardTransaction::getPoints).sum());

        RewardLedgerService ledger = new RewardLedgerService(cardRepo, ledgerRepo);
        cardService = new CardService(cardRepo, ledger);
    }

    private static Long eqCard() { return CARD; }
    private static Long eqUser() { return USER; }

    private int ledgerBalance() {
        return ledgerRows.stream().mapToInt(RewardTransaction::getPoints).sum();
    }

    @Test
    @DisplayName("a legacy card's stored balance and its ledger agree after a manual correction")
    void manualCorrectionOnALegacyCardKeepsBothWritersInStep() {
        card = CreditCard.builder().id(CARD).userId(USER).name("HDFC Regalia")
            .pointsBalance(1000).build();

        cardService.updatePoints(USER, CARD, 5000);

        assertThat(card.getPointsBalance()).isEqualTo(5000);
        assertThat(ledgerBalance()).isEqualTo(5000);
    }

    @Test
    void repeatedCorrectionsStayInStep() {
        card = CreditCard.builder().id(CARD).userId(USER).name("HDFC Regalia")
            .pointsBalance(1000).build();

        cardService.updatePoints(USER, CARD, 5000);
        cardService.updatePoints(USER, CARD, 4200);
        cardService.updatePoints(USER, CARD, 9000);

        assertThat(card.getPointsBalance()).isEqualTo(9000);
        assertThat(ledgerBalance()).isEqualTo(9000);
    }

    @Test
    void aCardThatNeverHadAStoredBalanceNeedsNoOpeningRow() {
        card = CreditCard.builder().id(CARD).userId(USER).name("New Card")
            .pointsBalance(0).build();

        cardService.updatePoints(USER, CARD, 750);

        assertThat(ledgerRows).hasSize(1);
        assertThat(ledgerBalance()).isEqualTo(750);
        assertThat(card.getPointsBalance()).isEqualTo(750);
    }

    @Test
    void correctingToTheSameValueWritesNothing() {
        card = CreditCard.builder().id(CARD).userId(USER).name("HDFC Regalia")
            .pointsBalance(1000).build();

        cardService.updatePoints(USER, CARD, 1000);

        assertThat(ledgerRows).isEmpty();
        assertThat(card.getPointsBalance()).isEqualTo(1000);
    }
}
