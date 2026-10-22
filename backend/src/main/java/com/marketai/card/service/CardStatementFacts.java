package com.marketai.card.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The figures a card statement states, already checked against the statement's own text. */
public record CardStatementFacts(String issuer, String cardLast4, LocalDate statementDate, LocalDate dueDate,
                                 BigDecimal totalDue, BigDecimal minimumDue, BigDecimal previousBalance,
                                 BigDecimal creditLimit, BigDecimal availableLimit, String flagsJson,
                                 List<Line> lines) {

    public CardStatementFacts {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One transaction line printed on the statement. {@code debit} is a charge; a credit is a payment or refund. */
    public record Line(String merchant, BigDecimal amount, LocalDate date, String currency, boolean debit,
                       String category, String evidence) {}
}
