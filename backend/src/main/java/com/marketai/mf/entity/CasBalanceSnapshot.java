package com.marketai.mf.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One CAS/AMC statement's stated closing unit balance for a folio, on a given date — the
 * "what the statement itself says you hold" fact, independent of whatever this app's own
 * transaction ledger computes for the same folio.
 *
 * <p>Written only from a span-verified {@code closing_balances[]} entry extracted by
 * {@code EmailLLMParserService} (see {@code SpanVerifier}) from a sender that passed
 * {@code SenderTrustEvaluator} — the same grounding and trust gates every transaction goes
 * through. Never derived or estimated from transaction rows: it exists specifically to be an
 * independent check on them.
 *
 * <p>Rows are append-only, one per (userId, folio, schemeCode, asOfDate) statement observed —
 * never updated in place — so {@code MfCasUnitMismatchCheck} always has the actual history of
 * what each statement claimed, not just the latest overwritten figure.
 */
@Entity
@Table(name = "cas_balance_snapshots",
        indexes = {
                @Index(name = "idx_cas_balance_user_folio", columnList = "user_id, folio"),
                @Index(name = "idx_cas_balance_user_folio_scheme", columnList = "user_id, folio, scheme_code")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CasBalanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 60)
    private String folio;

    /**
     * AMFI scheme code, resolved the same way {@code EmailLLMParserService} resolves a
     * transaction's scheme name — via {@code MfSchemeLinkService}. Nullable: a statement's
     * closing-balance line is still worth recording (matchable by folio alone) even when the
     * scheme name on that line doesn't confidently resolve.
     */
    @Column(name = "scheme_code", length = 20)
    private String schemeCode;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "stated_units", nullable = false, precision = 18, scale = 4)
    private BigDecimal statedUnits;

    /** Traceability back to the source email this snapshot was read from. */
    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
