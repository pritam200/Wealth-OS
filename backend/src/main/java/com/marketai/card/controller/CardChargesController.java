package com.marketai.card.controller;

import com.marketai.auth.entity.User;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.service.CardStatementAnalyzer;
import com.marketai.card.service.CardStatementAnalyzer.Flag;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every bank charge and unusual item found on any card statement read from email, across all of
 * the user's cards — the flags are computed when each statement is read, and gathered here.
 */
@RestController
@RequestMapping("/api/cards/charges")
@RequiredArgsConstructor
public class CardChargesController {

    private final CreditCardRepository cardRepository;
    private final CardStatementRepository statementRepository;

    @Data @Builder
    public static class FlaggedStatement {
        private Long cardId;
        private String cardName;
        private String cardLastFour;
        private Long statementId;
        private LocalDate statementDate;
        private LocalDate dueDate;
        private List<Flag> flags;
    }

    @Data @Builder
    public static class ChargesReport {
        /** How many statements were read and checked, flagged or not. */
        private int statementsChecked;
        /** Sum of the charges the bank levied (fees, interest, GST) over the statements on file. */
        private BigDecimal bankChargesTotal;
        private int unusualCount;
        /** Statements with at least one flag, newest first. */
        private List<FlaggedStatement> statements;
    }

    @GetMapping
    public ResponseEntity<ChargesReport> charges(@AuthenticationPrincipal User user) {
        Map<Long, CreditCard> cards = cardRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .collect(Collectors.toMap(CreditCard::getId, Function.identity()));
        List<CardStatement> all = statementRepository.findByUserId(user.getId()).stream()
            .filter(s -> cards.containsKey(s.getCardId())).toList();

        BigDecimal charges = BigDecimal.ZERO;
        int unusual = 0;
        List<FlaggedStatement> out = new ArrayList<>();
        for (CardStatement s : all) {
            List<Flag> flags = CardStatementAnalyzer.flagsFor(s);
            if (flags.isEmpty()) continue;
            for (Flag f : flags) {
                if (CardStatementAnalyzer.isBankCharge(f)) {
                    if (f.amount() != null) charges = charges.add(f.amount());
                } else {
                    unusual++;
                }
            }
            CreditCard c = cards.get(s.getCardId());
            out.add(FlaggedStatement.builder()
                .cardId(c.getId()).cardName(c.getName()).cardLastFour(c.getLastFour())
                .statementId(s.getId()).statementDate(s.getStatementDate()).dueDate(s.getDueDate())
                .flags(flags).build());
        }
        out.sort(Comparator.comparing((FlaggedStatement f) -> f.getStatementDate() != null ? f.getStatementDate() : f.getDueDate(),
            Comparator.nullsLast(Comparator.reverseOrder())));
        return ResponseEntity.ok(ChargesReport.builder()
            .statementsChecked(all.size()).bankChargesTotal(charges).unusualCount(unusual).statements(out).build());
    }
}
