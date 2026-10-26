package com.marketai.card.service;

import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CardStatementLine;
import com.marketai.card.entity.CardStatementLine.Status;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardStatementLineRepository;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Ticks a card statement's lines off against the expenses already on record, and books the
 * charges no earlier sync caught.
 *
 * <p>A spend usually reaches the ledger first from the card's own transaction alert, so most
 * lines should find their expense. A line that doesn't — the alert was never sent, was filtered,
 * or went to review — is a real charge the expense list is missing, and is added from the
 * statement. Each added expense says where it came from, and the user can take it back out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardStatementTallyService {

    /** A card posts a charge a few days after it happens; the alert carries the earlier date. */
    static final int DATE_WINDOW_DAYS = 4;
    /** Rounding on the statement against the alert (paise dropped, or a fee folded in). */
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.00");

    private final CardStatementLineRepository lineRepo;
    private final ExpenseRepository expenseRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.marketai.expense.service.MerchantCategoryRuleService merchantRules;

    public record Summary(int lines, int matched, int added, int missing, int credits) {}

    /** Stores the statement's lines (once) and tallies them. */
    @Transactional
    public Summary recordAndTally(CardStatement st, CreditCard card, List<CardStatementFacts.Line> lines) {
        if (!lineRepo.existsByStatementId(st.getId())) {
            List<CardStatementLine> rows = new ArrayList<>();
            for (CardStatementFacts.Line l : lines) {
                if (l.amount() == null || l.amount().signum() == 0) continue;
                rows.add(CardStatementLine.builder()
                    .statementId(st.getId()).cardId(card.getId()).userId(st.getUserId())
                    .txnDate(l.date()).merchant(trim(l.merchant(), 200)).amount(l.amount().abs())
                    .debit(l.debit()).currency(trim(l.currency(), 8)).category(trim(l.category(), 60))
                    .evidence(trim(l.evidence(), 500))
                    .status(l.debit() ? Status.MISSING : Status.CREDIT)
                    .build());
            }
            lineRepo.saveAll(rows);
        }
        return tally(st, card);
    }

    /**
     * Re-runs the tally for every line still MISSING. Lines already matched, added, or removed by
     * the user are left as they are, so running it twice never books a charge twice.
     */
    @Transactional
    public Summary tally(CardStatement st, CreditCard card) {
        List<CardStatementLine> lines = lineRepo.findByStatementIdOrderByTxnDateAscIdAsc(st.getId());
        Set<Long> claimed = new HashSet<>(lineRepo.claimedExpenseIds(st.getUserId()));
        for (CardStatementLine line : lines) {
            if (line.getStatus() != Status.MISSING || line.getTxnDate() == null) continue;
            Expense match = bestMatch(line, st.getSourceEmailId(), claimed);
            if (match != null) {
                line.setStatus(Status.MATCHED);
                line.setMatchedExpenseId(match.getId());
                claimed.add(match.getId());
            } else {
                Expense added = expenseRepo.save(expenseFor(line, st, card));
                line.setStatus(Status.ADDED);
                line.setMatchedExpenseId(added.getId());
                claimed.add(added.getId());
            }
        }
        lineRepo.saveAll(lines);
        Summary s = summarise(lines);
        if (s.added() > 0) {
            log.info("Card statement {} ({}): {} of {} charge(s) were not in the expense list and were added",
                st.getId(), card.getName(), s.added(), s.lines() - s.credits());
        }
        return s;
    }

    /** Books a MISSING or IGNORED line as an expense, by the user's choice. */
    @Transactional
    public CardStatementLine addAsExpense(CardStatementLine line, CardStatement st, CreditCard card) {
        if (!line.isDebit()) throw new IllegalArgumentException("A payment or refund is not an expense.");
        if (line.getStatus() == Status.MATCHED || line.getStatus() == Status.ADDED) return line;
        if (line.getTxnDate() == null) line.setTxnDate(st.getStatementDate() != null ? st.getStatementDate() : LocalDate.now());
        Expense added = expenseRepo.save(expenseFor(line, st, card));
        line.setStatus(Status.ADDED);
        line.setMatchedExpenseId(added.getId());
        return lineRepo.save(line);
    }

    /** Takes back an expense this tally added; the line stays on the statement as IGNORED. */
    @Transactional
    public CardStatementLine removeAddedExpense(CardStatementLine line) {
        if (line.getStatus() != Status.ADDED) return line;
        if (line.getMatchedExpenseId() != null) {
            expenseRepo.findByIdAndUserId(line.getMatchedExpenseId(), line.getUserId()).ifPresent(expenseRepo::delete);
        }
        line.setMatchedExpenseId(null);
        line.setStatus(Status.IGNORED);
        return lineRepo.save(line);
    }

    public Summary summarise(List<CardStatementLine> lines) {
        int matched = 0, added = 0, missing = 0, credits = 0;
        for (CardStatementLine l : lines) {
            switch (l.getStatus()) {
                case MATCHED -> matched++;
                case ADDED -> added++;
                case MISSING, IGNORED -> missing++;
                case CREDIT -> credits++;
            }
        }
        return new Summary(lines.size(), matched, added, missing, credits);
    }

    /** The expense this line most plausibly is: same amount, close date, preferring this email and a similar merchant. */
    private Expense bestMatch(CardStatementLine line, String statementEmailId, Set<Long> claimed) {
        LocalDate d = line.getTxnDate();
        List<Expense> near = expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(
            line.getUserId(), d.minusDays(DATE_WINDOW_DAYS), d.plusDays(DATE_WINDOW_DAYS));
        Expense best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Expense e : near) {
            if (claimed.contains(e.getId()) || e.getAmount() == null) continue;
            // A card-bill payment settles spends already counted; it is never one of them.
            if (e.getCategory() != null && !e.getCategory().isSpending()) continue;
            if (e.getAmount().subtract(line.getAmount()).abs().compareTo(AMOUNT_TOLERANCE) > 0) continue;
            double score = -Math.abs(ChronoUnit.DAYS.between(d, e.getExpenseDate()));
            if (statementEmailId != null && statementEmailId.equals(e.getSourceEmailId())) score += 5;
            score += 3 * merchantSimilarity(line.getMerchant(), e.getMerchant() != null ? e.getMerchant() : e.getDescription());
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    /** Share of the shorter name's words found in the other, 0–1. "AMAZON PAY INDIA" vs "Amazon" = 1. */
    static double merchantSimilarity(String a, String b) {
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> small = ta.size() <= tb.size() ? ta : tb, large = small == ta ? tb : ta;
        long hit = small.stream().filter(large::contains).count();
        return (double) hit / small.size();
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (t.length() >= 3) out.add(t);
        return out;
    }

    private Expense expenseFor(CardStatementLine line, CardStatement st, CreditCard card) {
        String merchant = line.getMerchant();
        String cardLabel = card.getName() + (card.getLastFour() != null ? " ••" + card.getLastFour() : "");
        return Expense.builder()
            .userId(line.getUserId())
            .description(merchant != null ? merchant : "Card spend")
            .amount(line.getAmount())
            .category(categoryFor(line))
            .expenseDate(line.getTxnDate())
            .merchant(merchant)
            .paymentMethod(trim("Credit Card · " + cardLabel, 100))
            .sourceEmailId(st.getSourceEmailId())
            .sourceFingerprint("cardline:" + line.getId())
            .note(trim("Added from the " + cardLabel + " statement"
                + (st.getStatementDate() != null ? " of " + st.getStatementDate() : "")
                + " — no earlier sync had this charge.", 500))
            .build();
    }

    /**
     * The user's remembered category for the merchant, else the issuer's label when it is one of
     * ours, else a guess from the line's own text: issuer labels like "Dining" or "Retail" are not
     * our categories and used to land every added line in Uncategorized.
     */
    private ExpenseCategory categoryFor(CardStatementLine line) {
        if (merchantRules != null) {
            var remembered = merchantRules.categoryFor(line.getUserId(), line.getMerchant());
            if (remembered.isPresent()) return remembered.get();
        }
        ExpenseCategory issuer = ExpenseCategory.fromLabel(line.getCategory());
        if (issuer != ExpenseCategory.UNCATEGORIZED) return issuer;
        String text = (line.getMerchant() != null ? line.getMerchant() : "") + " " + (line.getEvidence() != null ? line.getEvidence() : "");
        return com.marketai.gmail.parser.SpendCategorizer.categorize(text);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
