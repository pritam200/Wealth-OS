package com.marketai.reconciliation.check;

import com.marketai.card.entity.CardStatement;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Card statements whose own arithmetic doesn't close.
 *
 * <p>{@code ArithmeticValidator.statementBalances} checks previous balance + this cycle's
 * debits − this cycle's credits against the statement's own stated total due — the strongest
 * available signal that an extracted figure is wrong, because it uses the document's internal
 * redundancy rather than trusting any single extracted number. That check is run at import time
 * in {@code ParsedEmailImporter.applyCardBill} and the result is stored on the
 * {@link CardStatement} row rather than acted on there, for the same reason every other check in
 * this package reports rather than repairs: the app cannot know which figure was misread, and
 * guessing would fabricate a financial record. This check is what actually surfaces that stored
 * result to the user, since nothing else in the reconciliation report reads it.
 */
@Component
@RequiredArgsConstructor
public class StatementArithmeticMismatchCheck implements ReconciliationCheck {

    private final CardStatementRepository cardStatementRepository;

    @Override public String id() { return "CARD_STATEMENT_ARITHMETIC_MISMATCH"; }
    @Override public String domain() { return "INGESTION"; }
    @Override public String description() {
        return "A card statement's own previous-balance + debits − credits does not reconcile "
             + "to its stated total due — the extraction likely misread a figure";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<CardStatement> mismatched = cardStatementRepository.findByUserIdAndArithmeticMismatchTrue(userId);
        if (mismatched.isEmpty()) return List.of();

        List<ReconciliationIssue> issues = new ArrayList<>();
        for (CardStatement s : mismatched) {
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("HIGH")
                .referenceId(s.getId())
                .description(String.format(
                    "Card statement dated %s does not reconcile: %s. Check the original statement "
                        + "before trusting this figure.",
                    s.getStatementDate() != null ? s.getStatementDate() : "unknown",
                    s.getArithmeticMismatchDetail() != null ? s.getArithmeticMismatchDetail() : "amounts do not add up"))
                .build());
        }
        return issues;
    }
}
