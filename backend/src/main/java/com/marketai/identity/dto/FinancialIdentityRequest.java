package com.marketai.identity.dto;

import java.time.LocalDate;

/**
 * Inbound only. Both fields are optional so one can be updated without resending the other —
 * which matters because the client can never read back what is stored, and would otherwise have
 * to ask the user to retype a value it cannot see.
 */
public record FinancialIdentityRequest(String pan, LocalDate dateOfBirth) {}
