package com.marketai.card.service;

import com.marketai.card.dto.CardStatementDtos.ApplyResult;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Copies what a statement email stated onto the saved card, when the person asks for it. Only
 * values the statement actually carries are applied, and what changed is reported back.
 */
@Service
@RequiredArgsConstructor
public class CardStatementApplyService {

    private final CreditCardRepository cardRepository;
    private final CardStatementRepository statementRepository;

    /** The newest statement on file for the card, by statement date (due date when it has none). */
    public CardStatement latest(Long cardId) {
        return statementRepository.findByCardIdOrderByDueDateDesc(cardId).stream()
            .max(Comparator.comparing((CardStatement s) -> s.getStatementDate() != null ? s.getStatementDate() : s.getDueDate(),
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    @Transactional
    public ApplyResult apply(Long userId, Long cardId, Long statementId) {
        CreditCard card = cardRepository.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        CardStatement st = statementId != null ? statementRepository.findById(statementId).orElse(null) : latest(cardId);
        if (st == null || !cardId.equals(st.getCardId()) || !userId.equals(st.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No statement found for this card");
        }
        List<String> changes = new ArrayList<>();

        if ((card.getLastFour() == null || card.getLastFour().isBlank()) && st.getCardLastFour() != null) {
            card.setLastFour(st.getCardLastFour());
            changes.add("Card ending " + st.getCardLastFour());
        }
        if (st.getCreditLimit() != null && st.getCreditLimit().compareTo(nz(card.getCreditLimit())) != 0) {
            card.setCreditLimit(st.getCreditLimit());
            changes.add("Credit limit ₹" + st.getCreditLimit().toPlainString());
        }
        // Like the bill import: the "due now" figure only moves forward, never back to an older cycle.
        boolean newer = card.getCurrentDueDate() == null || st.getDueDate() == null
            || !st.getDueDate().isBefore(card.getCurrentDueDate());
        // A credit balance is nothing due, not a negative bill.
        java.math.BigDecimal due = st.getTotalDue().max(java.math.BigDecimal.ZERO);
        if (newer && (due.compareTo(nz(card.getCurrentDue())) != 0
                || (st.getDueDate() != null && !st.getDueDate().equals(card.getCurrentDueDate())))) {
            card.setCurrentDue(due);
            if (st.getDueDate() != null) card.setCurrentDueDate(st.getDueDate());
            changes.add("Amount due ₹" + due.toPlainString()
                + (st.getDueDate() != null ? " by " + st.getDueDate() : ""));
        }
        if (newer && st.getDueDate() != null && !Integer.valueOf(st.getDueDate().getDayOfMonth()).equals(card.getDueDay())) {
            card.setDueDay(st.getDueDate().getDayOfMonth());
            changes.add("Due day " + st.getDueDate().getDayOfMonth());
        }
        LocalDate sd = st.getStatementDate();
        if (newer && sd != null && !Integer.valueOf(sd.getDayOfMonth()).equals(card.getBillingDay())) {
            card.setBillingDay(sd.getDayOfMonth());
            changes.add("Billing day " + sd.getDayOfMonth());
        }
        if (!changes.isEmpty()) cardRepository.save(card);
        return ApplyResult.builder().changes(changes).build();
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.valueOf(-1) : v;
    }
}
