package com.marketai.gmail.service;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The weighted layer between the two exact-match gates in ParsedEmailImporter: catches the
 * spec's core example (a transaction alert and a later statement line describing the same real
 * purchase, which hash differently) while NOT collapsing two genuinely separate ₹5,000 Amazon
 * purchases on the same day into one.
 */
class TransactionMatchScorerTest {

    private static final Long USER = 1L;

    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private TransactionMatchScorer scorer;

    @BeforeEach
    void setUp() {
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);
        scorer = new TransactionMatchScorer(fingerprintRepo);
    }

    private ImportedTransactionFingerprint existing(BigDecimal amount, LocalDate date, String merchant, String cardLast4) {
        return ImportedTransactionFingerprint.builder()
            .id(100L).userId(USER).fingerprint("irrelevant")
            .amount(amount).transactionDate(date).merchant(merchant).cardLast4(cardLast4)
            .build();
    }

    private ParsedEmail expense(BigDecimal amount, LocalDate date, String merchant, String cardLast4) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .amount(amount).tradeDate(date).merchant(merchant).cardLast4(cardLast4)
            .build();
    }

    @Test
    @DisplayName("same amount, same day, same merchant, same card scores above the confirm threshold")
    void strongMatchScoresHigh() {
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("4500.00")))
            .thenReturn(List.of(existing(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234")));

        ParsedEmail candidate = expense(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234");
        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, candidate);

        assertThat(match).isPresent();
        assertThat(match.get().getConfidence()).isGreaterThanOrEqualTo(TransactionMatchScorer.CONFIRM_THRESHOLD);
    }

    @Test
    @DisplayName("a statement line a few days after the alert, same amount/merchant/card, still matches (near-date band)")
    void nearDateStillMatches() {
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("4500.00")))
            .thenReturn(List.of(existing(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234")));

        ParsedEmail candidate = expense(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 12), "AMAZON", "1234");
        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, candidate);

        assertThat(match).isPresent();
        assertThat(match.get().getConfidence()).isGreaterThanOrEqualTo(TransactionMatchScorer.REVIEW_THRESHOLD);
    }

    @Test
    @DisplayName("no candidates in the amount pool means no match at all — treated as NEW")
    void noCandidatesIsNoMatch() {
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("4500.00"))).thenReturn(List.of());

        Optional<TransactionMatchScorer.ScoredMatch> match =
            scorer.findBestMatch(USER, expense(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234"));

        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("same amount and merchant but a different card and a far-apart date scores below review — two separate purchases")
    void weakSignalsDoNotMatch() {
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("5000.00")))
            .thenReturn(List.of(existing(new BigDecimal("5000.00"), LocalDate.of(2026, 1, 3), "Amazon", "9999")));

        ParsedEmail candidate = expense(new BigDecimal("5000.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234");
        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, candidate);

        // Only the merchant-exact signal (0.10) plus the guaranteed amount signal (0.15) apply —
        // 0.25 total, below REVIEW_THRESHOLD (0.45) — proving two legitimately separate ₹5,000
        // Amazon purchases are not flagged as duplicates just because amount+merchant coincide.
        assertThat(match).isEmpty();
    }

    @Test
    @DisplayName("non-scored types (trades, FDs) are never sent through the weighted matcher")
    void tradesAreOutOfScope() {
        ParsedEmail trade = ParsedEmail.builder()
            .type(ParsedEmail.Type.TRADE_BUY)
            .amount(new BigDecimal("1000.00"))
            .symbol("RELIANCE").quantity(1).price(new BigDecimal("1000.00"))
            .build();

        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, trade);

        assertThat(match).isEmpty();
        // The repository must never even be queried for an out-of-scope type.
        org.mockito.Mockito.verifyNoInteractions(fingerprintRepo);
    }

    @Test
    @DisplayName("a null amount candidate is never scored (no basis to narrow the candidate pool)")
    void nullAmountIsNeverScored() {
        ParsedEmail noAmount = ParsedEmail.builder().type(ParsedEmail.Type.EXPENSE).merchant("Amazon").build();

        assertThat(scorer.findBestMatch(USER, noAmount)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(fingerprintRepo);
    }

    @Test
    @DisplayName("a matching CARD_PAYMENT reference against the stored externalRef scores very high")
    void referenceMatchIsStrongSignal() {
        ImportedTransactionFingerprint priorStatementLine = existing(new BigDecimal("2000.00"), LocalDate.of(2026, 3, 4), null, "1234");
        priorStatementLine.setExternalRef("RRN999");
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("2000.00")))
            .thenReturn(List.of(priorStatementLine));

        ParsedEmail candidate = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .amount(new BigDecimal("2000.00")).paymentDate(LocalDate.of(2026, 3, 4))
            .cardLast4("1234").paymentReference("RRN999")
            .build();

        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, candidate);
        assertThat(match).isPresent();
        assertThat(match.get().getConfidence()).isGreaterThanOrEqualTo(TransactionMatchScorer.CONFIRM_THRESHOLD);
    }

    @Test
    @DisplayName("when multiple candidates exist, the highest-scoring one wins")
    void bestOfMultipleCandidatesWins() {
        ImportedTransactionFingerprint weak = existing(new BigDecimal("4500.00"), LocalDate.of(2026, 1, 1), null, null);
        ImportedTransactionFingerprint strong = existing(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234");
        when(fingerprintRepo.findByUserIdAndAmount(USER, new BigDecimal("4500.00")))
            .thenReturn(List.of(weak, strong));

        ParsedEmail candidate = expense(new BigDecimal("4500.00"), LocalDate.of(2026, 9, 10), "Amazon", "1234");
        Optional<TransactionMatchScorer.ScoredMatch> match = scorer.findBestMatch(USER, candidate);

        assertThat(match).isPresent();
        assertThat(match.get().getMatched()).isSameAs(strong);
    }
}
