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
    private final com.marketai.card.service.CardStatementApplyService applyService;
    private final com.marketai.card.repository.CardStatementLineRepository lineRepository;
    private final com.marketai.card.service.CardStatementTallyService tallyService;
    private final com.marketai.expense.repository.ExpenseRepository expenseRepository;

    @GetMapping("/statements")
    public ResponseEntity<List<StatementResponse>> statements(@AuthenticationPrincipal User user,
                                                               @PathVariable Long cardId) {
        requireOwnedCard(user.getId(), cardId);
        List<StatementResponse> out = statementRepository.findByCardIdOrderByDueDateDesc(cardId).stream()
            .map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    /** Copies the latest statement's figures (or the one given) onto the card. */
    @PostMapping("/apply-statement")
    public ResponseEntity<ApplyResult> applyStatement(@AuthenticationPrincipal User user, @PathVariable Long cardId,
                                                      @RequestParam(required = false) Long statementId) {
        return ResponseEntity.ok(applyService.apply(user.getId(), cardId, statementId));
    }

    /** One statement with every line and what the tally found for it. */
    @GetMapping("/statements/{statementId}")
    public ResponseEntity<StatementDetail> statement(@AuthenticationPrincipal User user, @PathVariable Long cardId,
                                                     @PathVariable Long statementId) {
        CardStatement st = requireStatement(user.getId(), cardId, statementId);
        return ResponseEntity.ok(detail(st));
    }

    /** Re-checks the statement's still-missing lines against expenses, adding any no sync caught. */
    @PostMapping("/statements/{statementId}/tally")
    public ResponseEntity<StatementDetail> tally(@AuthenticationPrincipal User user, @PathVariable Long cardId,
                                                 @PathVariable Long statementId) {
        CardStatement st = requireStatement(user.getId(), cardId, statementId);
        tallyService.tally(st, cardRepository.findByIdAndUserId(cardId, user.getId()).orElseThrow());
        return ResponseEntity.ok(detail(st));
    }

    /** Books a line as an expense ("add") or takes back an expense the tally added ("remove"). */
    @PostMapping("/statement-lines/{lineId}/{action}")
    public ResponseEntity<StatementDetail> lineAction(@AuthenticationPrincipal User user, @PathVariable Long cardId,
                                                      @PathVariable Long lineId, @PathVariable String action) {
        var line = lineRepository.findByIdAndUserId(lineId, user.getId())
            .filter(l -> l.getCardId().equals(cardId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Statement line not found"));
        CardStatement st = requireStatement(user.getId(), cardId, line.getStatementId());
        var card = cardRepository.findByIdAndUserId(cardId, user.getId()).orElseThrow();
        switch (action) {
            case "add" -> {
                try { tallyService.addAsExpense(line, st, card); }
                catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
            }
            case "remove" -> tallyService.removeAddedExpense(line);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action " + action);
        }
        return ResponseEntity.ok(detail(st));
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

    private CardStatement requireStatement(Long userId, Long cardId, Long statementId) {
        requireOwnedCard(userId, cardId);
        return statementRepository.findById(statementId)
            .filter(s -> s.getCardId().equals(cardId) && s.getUserId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Statement not found"));
    }

    private StatementDetail detail(CardStatement st) {
        var lines = lineRepository.findByStatementIdOrderByTxnDateAscIdAsc(st.getId());
        java.util.Map<Long, com.marketai.expense.entity.Expense> expenses = new java.util.HashMap<>();
        var ids = lines.stream().map(com.marketai.card.entity.CardStatementLine::getMatchedExpenseId)
            .filter(java.util.Objects::nonNull).toList();
        if (!ids.isEmpty()) expenseRepository.findAllById(ids).forEach(e -> expenses.put(e.getId(), e));
        List<LineResponse> out = lines.stream().map(l -> {
            var e = l.getMatchedExpenseId() != null ? expenses.get(l.getMatchedExpenseId()) : null;
            return LineResponse.builder()
                .id(l.getId()).txnDate(l.getTxnDate()).merchant(l.getMerchant()).amount(l.getAmount())
                .debit(l.isDebit()).currency(l.getCurrency()).category(l.getCategory()).evidence(l.getEvidence())
                .status(l.getStatus().name()).expenseId(l.getMatchedExpenseId())
                .expenseDate(e != null ? e.getExpenseDate() : null)
                .expenseDescription(e != null ? e.getDescription() : null).build();
        }).toList();
        return StatementDetail.builder().statement(toDto(st)).lines(out).build();
    }

    private TallySummary tallyOf(CardStatement s) {
        var lines = lineRepository.findByStatementIdOrderByTxnDateAscIdAsc(s.getId());
        if (lines.isEmpty()) return null;
        var sum = tallyService.summarise(lines);
        java.math.BigDecimal debits = java.math.BigDecimal.ZERO, credits = java.math.BigDecimal.ZERO;
        for (var l : lines) {
            if (l.isDebit()) debits = debits.add(l.getAmount()); else credits = credits.add(l.getAmount());
        }
        return TallySummary.builder().lines(sum.lines()).matched(sum.matched()).added(sum.added())
            .missing(sum.missing()).credits(sum.credits()).debitTotal(debits).creditTotal(credits).build();
    }

    private void requireOwnedCard(Long userId, Long cardId) {
        cardRepository.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
    }

    private StatementResponse toDto(CardStatement s) {
        var flags = com.marketai.card.service.CardStatementAnalyzer.flagsFor(s);
        return StatementResponse.builder()
            .creditLimit(s.getCreditLimit()).availableLimit(s.getAvailableLimit())
            .cardLastFour(s.getCardLastFour()).flags(flags).tally(tallyOf(s))
            .id(s.getId()).statementDate(s.getStatementDate()).dueDate(s.getDueDate())
            .totalDue(s.getTotalDue()).minimumDue(s.getMinimumDue())
            .previousBalance(s.getPreviousBalance())
            .arithmeticMismatch(s.getArithmeticMismatch())
            .arithmeticMismatchDetail(s.getArithmeticMismatchDetail())
            .sourceEmailId(s.getSourceEmailId()).build();
    }

    private PaymentResponse toDto(CardPayment p) {
        return PaymentResponse.builder()
            .id(p.getId()).amount(p.getAmount()).paymentDate(p.getPaymentDate())
            .referenceNumber(p.getReferenceNumber()).status(p.getStatus().name())
            .sourceEmailId(p.getSourceEmailId()).build();
    }
}
