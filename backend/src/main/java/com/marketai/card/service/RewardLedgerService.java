package com.marketai.card.service;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.RewardTransaction;
import com.marketai.card.entity.RewardTransactionType;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.repository.RewardTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source of truth for a card's points balance once it has any ledger history. A card added
 * before this ledger existed has no rows yet — for that card only, the balance falls back to
 * the legacy stored {@code CreditCard.pointsBalance} value, exactly the same "no history yet,
 * trust the last known figure" pattern used for a holding's stored XIRR before real transactions
 * exist to compute one.
 */
@Service
@RequiredArgsConstructor
public class RewardLedgerService {

    private final CreditCardRepository cardRepository;
    private final RewardTransactionRepository ledgerRepository;

    public int getBalance(Long cardId, Integer fallbackStoredBalance) {
        if (!ledgerRepository.existsByCardId(cardId)) {
            return fallbackStoredBalance != null ? fallbackStoredBalance : 0;
        }
        return ledgerRepository.sumPointsByCardId(cardId);
    }

    /**
     * Reward value already earned on this card in the calendar month containing {@code asOf}.
     *
     * <p>A monthly cashback cap is a running counter, not a per-transaction ceiling. Without this
     * the optimizer compared each transaction's reward against the full cap in isolation, so a
     * card whose ₹5,000 monthly cap was already exhausted was still ranked first on a new ₹20,000
     * purchase at a notional ₹1,000 reward — when its real marginal reward was the base rate.
     */
    public BigDecimal earnedValueInMonth(Long cardId, LocalDate asOf) {
        LocalDate day = asOf != null ? asOf : LocalDate.now();
        LocalDate from = day.withDayOfMonth(1);
        LocalDate to = day.withDayOfMonth(day.lengthOfMonth());
        BigDecimal earned = ledgerRepository.sumEarnedValueBetween(cardId, from, to);
        return earned != null ? earned : BigDecimal.ZERO;
    }

    public List<RewardTransaction> history(Long userId, Long cardId) {
        requireOwnedCard(userId, cardId);
        return ledgerRepository.findByCardIdOrderByTransactionDateDescCreatedAtDesc(cardId);
    }

    public RewardTransaction earn(Long userId, Long cardId, int points, BigDecimal monetaryValue,
                                  LocalDate date, String note) {
        CreditCard card = requireOwnedCard(userId, cardId);
        if (points <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Earned points must be positive");
        }
        ensureOpeningBalance(card);
        return ledgerRepository.save(RewardTransaction.builder()
            .cardId(cardId).userId(userId).type(RewardTransactionType.EARN)
            .points(points).monetaryValue(monetaryValue)
            .transactionDate(date != null ? date : LocalDate.now())
            .note(note).build());
    }

    public RewardTransaction redeem(Long userId, Long cardId, int points, BigDecimal monetaryValue,
                                    LocalDate date, String note) {
        CreditCard card = requireOwnedCard(userId, cardId);
        if (points <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Redeemed points must be positive");
        }
        int balance = getBalance(cardId, card.getPointsBalance());
        if (points > balance) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot redeem " + points + " points — balance is only " + balance);
        }
        ensureOpeningBalance(card);
        return ledgerRepository.save(RewardTransaction.builder()
            .cardId(cardId).userId(userId).type(RewardTransactionType.REDEEM)
            .points(-points).monetaryValue(monetaryValue)
            .transactionDate(date != null ? date : LocalDate.now())
            .note(note).build());
    }

    /** For the legacy "set absolute balance" edit box — recorded as a delta so the audit trail
     *  still explains where the number moved, rather than silently overwriting it. */
    public void adjustToAbsolute(Long userId, Long cardId, int newAbsoluteBalance, String note) {
        CreditCard card = requireOwnedCard(userId, cardId);
        int current = getBalance(cardId, card.getPointsBalance());
        int delta = newAbsoluteBalance - current;
        if (delta == 0) return;
        ensureOpeningBalance(card);
        ledgerRepository.save(RewardTransaction.builder()
            .cardId(cardId).userId(userId).type(RewardTransactionType.ADJUST)
            .points(delta).monetaryValue(null)
            .transactionDate(LocalDate.now())
            .note(note).build());
    }

    /**
     * Writes the legacy stored balance into the ledger as an opening entry before the card's
     * first real entry lands.
     *
     * <p>Without this, the stored figure is lost the instant any history exists: {@link #getBalance}
     * switches to the ledger sum, so a card carrying 1,000 points that redeems 300 would report
     * −300, and a card whose stored balance was set to 5,000 by {@code CardService.updatePoints}
     * would replay to 4,000 — the cached column and the ledger replay disagreeing, which is the
     * two-writer divergence the ledger exists to rule out. The opening row is an explicit,
     * auditable row rather than a silent adjustment, so the balance stays traceable.
     */
    private void ensureOpeningBalance(CreditCard card) {
        Integer stored = card.getPointsBalance();
        if (stored == null || stored == 0) return;
        if (ledgerRepository.existsByCardId(card.getId())) return;
        ledgerRepository.save(RewardTransaction.builder()
            .cardId(card.getId()).userId(card.getUserId()).type(RewardTransactionType.ADJUST)
            .points(stored).monetaryValue(null)
            .transactionDate(LocalDate.now())
            .note("Opening balance carried over from before the rewards ledger existed").build());
    }

    private CreditCard requireOwnedCard(Long userId, Long cardId) {
        return cardRepository.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
    }
}
