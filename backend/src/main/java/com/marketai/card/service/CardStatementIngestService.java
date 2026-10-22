package com.marketai.card.service;

import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Files a statement read from email onto its card: the statement's figures, its lines, and the tally against expenses. */
@Service
@RequiredArgsConstructor
public class CardStatementIngestService {

    private final CardStatementRepository statementRepo;
    private final CardStatementTallyService tallyService;

    /** @return the stored statement, or null when this email's statement is already on record. */
    @Transactional
    public CardStatement file(Long userId, CreditCard card, CardStatementFacts f, String gmailMessageId) {
        if (gmailMessageId != null && statementRepo.existsByUserIdAndSourceEmailId(userId, gmailMessageId)) return null;
        CardStatement st = statementRepo.save(CardStatement.builder()
            .cardId(card.getId()).userId(userId)
            .statementDate(f.statementDate()).dueDate(f.dueDate())
            .totalDue(f.totalDue()).minimumDue(f.minimumDue()).previousBalance(f.previousBalance())
            .creditLimit(f.creditLimit()).availableLimit(f.availableLimit())
            .cardLastFour(f.cardLast4()).flagsJson(f.flagsJson())
            .sourceEmailId(gmailMessageId).build());
        tallyService.recordAndTally(st, card, f.lines());
        return st;
    }
}
