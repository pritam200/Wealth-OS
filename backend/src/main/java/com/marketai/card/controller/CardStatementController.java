package com.marketai.card.controller;

import com.marketai.auth.entity.User;
import com.marketai.card.dto.CardStatementDtos.*;
import com.marketai.card.entity.CardPayment;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.repository.CardPaymentRepository;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.service.CardReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cards/{cardId}")
@RequiredArgsConstructor
public class CardStatementController {

    private final CreditCardRepository cardRepository;
    private final CardStatementRepository statementRepository;
    private final CardPaymentRepository paymentRepository;
    private final CardReconciliationService reconciliationService;

    @GetMapping("/statements")
    public ResponseEntity<List<StatementResponse>> statements(@AuthenticationPrincipal User user,
                                                               @PathVariable Long cardId) {
        requireOwnedCard(user.getId(), cardId);
        List<StatementResponse> out = statementRepository.findByCardIdOrderByDueDateDesc(cardId).stream()
            .map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/payments")
    public ResponseEntity<List<PaymentResponse>> payments(@AuthenticationPrincipal User user,
                                                           @PathVariable Long cardId) {
        requireOwnedCard(user.getId(), cardId);
        List<PaymentResponse> out = paymentRepository.findByCardIdOrderByPaymentDateDesc(cardId).stream()
            .map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<List<StatementReconciliation>> reconciliation(@AuthenticationPrincipal User user,
                                                                        @PathVariable Long cardId) {
        return ResponseEntity.ok(reconciliationService.reconcile(user.getId(), cardId));
    }

    private void requireOwnedCard(Long userId, Long cardId) {
        cardRepository.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
    }

    private StatementResponse toDto(CardStatement s) {
        return StatementResponse.builder()
            .id(s.getId()).statementDate(s.getStatementDate()).dueDate(s.getDueDate())
            .totalDue(s.getTotalDue()).minimumDue(s.getMinimumDue())
            .previousBalance(s.getPreviousBalance()).sourceEmailId(s.getSourceEmailId()).build();
    }

    private PaymentResponse toDto(CardPayment p) {
        return PaymentResponse.builder()
            .id(p.getId()).amount(p.getAmount()).paymentDate(p.getPaymentDate())
            .referenceNumber(p.getReferenceNumber()).status(p.getStatus().name())
            .sourceEmailId(p.getSourceEmailId()).build();
    }
}
