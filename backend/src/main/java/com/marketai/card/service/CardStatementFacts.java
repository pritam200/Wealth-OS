package com.marketai.card.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The figures a card statement states, already checked against the statement's own text. */
public record CardStatementFacts(String issuer, String cardLast4, LocalDate statementDate, LocalDate dueDate,
                                 BigDecimal totalDue, BigDecimal minimumDue, BigDecimal previousBalance,
                                 BigDecimal creditLimit, BigDecimal availableLimit, String flagsJson) {}
