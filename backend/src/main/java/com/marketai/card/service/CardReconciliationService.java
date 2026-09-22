package com.marketai.card.service;

import com.marketai.card.dto.CardStatementDtos.*;
import com.marketai.card.entity.CardPayment;
import com.marketai.card.entity.CardPaymentStatus;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.repository.CardPaymentRepository;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Statement due amounts and payment confirmations are both immutable, issuer-reported facts.
 * Outstanding balance is never one of them — it is always derived here, at read time, by
 * walking statements oldest-first and applying confirmed payments oldest-first, exactly the way
 * a real card ledger settles a running balance. Nothing is stored or mutated by this class;
 * running it twice on the same data always produces the same answer.
 *
 * <p>Explicitly out of scope, per spec: this class only explains what has already happened. It
 * never initiates, retries, or reverses a payment.
 */
@Service
@RequiredArgsConstructor
public class CardReconciliationService {

    private final CreditCardRepository cardRepository;
    private final CardStatementRepository statementRepository;
    private final CardPaymentRepository paymentRepository;

    public List<StatementReconciliation> reconcile(Long userId, Long cardId) {
        requireOwnedCard(userId, cardId);

        // Defensively copied before sorting — a repository (or, as in tests, a mock) is not
        // obligated to return a mutable list, and sorting in place must not depend on it doing so.
        List<CardStatement> statements = new ArrayList<>(statementRepository.findByCardIdOrderByDueDateDesc(cardId));
        statements.sort(Comparator.comparing(CardReconciliationService::cycleKey,
            Comparator.nullsLast(Comparator.naturalOrder())));

        // Only CONFIRMED payments settle a balance — a REVERSED one never happened as far as
        // the ledger is concerned, and is excluded rather than netted against.
        List<CardPayment> payments = paymentRepository.findByCardIdOrderByPaymentDateDesc(cardId).stream()
            .filter(p -> p.getStatus() == CardPaymentStatus.CONFIRMED)
            .sorted(Comparator.comparing(CardPayment::getPaymentDate))
            .collect(Collectors.toList());

        // Mutable per-payment remaining balance for the waterfall — local to this computation,
        // never persisted, so allocation can be recomputed from scratch every time new evidence
        // (a statement or a payment email) arrives without any prior allocation to unwind first.
        List<BigDecimal> remaining = new ArrayList<>();
        for (CardPayment p : payments) remaining.add(p.getAmount());

        List<StatementReconciliation> results = new ArrayList<>();
        for (CardStatement stmt : statements) {
            BigDecimal need = stmt.getTotalDue();
            BigDecimal applied = BigDecimal.ZERO;
            List<Long> matched = new ArrayList<>();

            for (int i = 0; i < payments.size() && need.signum() > 0; i++) {
                BigDecimal avail = remaining.get(i);
                if (avail.signum() <= 0) continue;
                BigDecimal take = avail.min(need);
                remaining.set(i, avail.subtract(take));
                need = need.subtract(take);
                applied = applied.add(take);
                matched.add(payments.get(i).getId());
            }

            BigDecimal outstanding = stmt.getTotalDue().subtract(applied).setScale(2, RoundingMode.HALF_UP);
            ReconciliationStatus status;
            if (outstanding.signum() < 0) status = ReconciliationStatus.OVERPAID;
            else if (outstanding.signum() == 0) status = ReconciliationStatus.PAID;
            else if (applied.signum() == 0) status = ReconciliationStatus.OUTSTANDING;
            else status = ReconciliationStatus.PARTIALLY_PAID;

            results.add(StatementReconciliation.builder()
                .statementId(stmt.getId())
                .statementDate(stmt.getStatementDate())
                .dueDate(stmt.getDueDate())
                .totalDue(stmt.getTotalDue())
                .amountApplied(applied.setScale(2, RoundingMode.HALF_UP))
                .outstanding(outstanding)
                .status(status)
                .matchedPaymentIds(matched)
                .build());
        }

        Collections.reverse(results); // most recent statement first, matching the API's other list endpoints
        return results;
    }

    private static LocalDate cycleKey(CardStatement s) {
        return s.getStatementDate() != null ? s.getStatementDate() : s.getDueDate();
    }

    private void requireOwnedCard(Long userId, Long cardId) {
        cardRepository.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
    }
}
