package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.dto.RdRequest;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The single place a {@link ParsedEmail} — regardless of whether it came from a
 * deterministic regex parser, the AI fallback extractor, or a decrypted PDF attachment —
 * gets booked into the right table (Portfolio/FD/RD/Income/Expense/Card). Extracted out of
 * GmailSyncService so the AI and PDF import paths reuse this routing instead of duplicating it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParsedEmailImporter {

    private final TrackingService trackingService;
    private final PortfolioService portfolioService;
    private final com.marketai.income.repository.IncomeRepository incomeRepo;
    private final com.marketai.expense.repository.ExpenseRepository expenseRepo;
    private final com.marketai.card.repository.CreditCardRepository cardRepo;
    private final com.marketai.card.repository.CardStatementRepository cardStatementRepo;
    private final com.marketai.card.repository.CardPaymentRepository cardPaymentRepo;
    private final com.marketai.tracking.repository.FixedDepositRepository fdRepo;
    private final com.marketai.tracking.repository.RecurringDepositRepository rdRepo;
    private final TransactionFingerprinter fingerprinter;
    private final com.marketai.gmail.repository.ImportedTransactionFingerprintRepository fingerprintRepo;
    private final com.marketai.document.identity.ReferenceHarvester referenceHarvester;
    private final TransactionMatchScorer matchScorer;
    private final com.marketai.rent.service.RentService rentService;

    public void importParsedEmail(Long userId, User user, ParsedEmail pe) throws Exception {
        importParsedEmail(userId, user, pe, null);
    }

    public void importParsedEmail(Long userId, User user, ParsedEmail pe, String gmailMessageId) throws Exception {
        importParsedEmail(userId, user, pe, gmailMessageId, null);
    }

    /**
     * @param documentText raw source text, used only to harvest payment-rail references. Pass
     *                     null when unavailable — reference matching is then simply skipped and
     *                     behaviour falls back to the content fingerprint.
     *
     * <p><b>Transactional because the financial record and its dedup fingerprint must commit
     * together.</b> Without it, a fingerprint write failing after the record was already booked
     * leaves the transaction in the ledger but unmarked — so the next sync sees it as new and
     * imports it a second time. Double-booking is the precise failure the fingerprint exists to
     * prevent, so the two writes cannot be allowed to diverge.
     *
     * <p>{@code rollbackFor = Exception.class} is required: this method declares a checked
     * exception, and Spring rolls back only on unchecked exceptions by default. Leaving the
     * default would mean a checked failure mid-import commits whatever had already been written.
     *
     * <p>Propagation is the default REQUIRED, and no caller holds a transaction, so each import
     * gets its own. That is deliberate — one bad email in a sync of two hundred must not roll
     * back the other hundred and ninety-nine.
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void importParsedEmail(Long userId, User user, ParsedEmail pe, String gmailMessageId,
                                  String documentText) throws Exception {

        // --- Tier 1: a rail-issued reference is decisive.
        //
        // Checked before the content hash because it catches duplicates the hash structurally
        // cannot: one trade reported as a bank debit (gross) and as a contract note (net) shares
        // no hashable field, but both quote the same UTR.
        //
        // Safe to act on automatically because it can only ever *prevent* a double-booking.
        // Globally-unique rail references identify exactly one payment, and the lookup is scoped
        // to this user and to the reference type, so a false positive would require the rail to
        // have issued one identifier for two payments.
        com.marketai.document.identity.ExternalReference ref = documentText == null ? null
            : referenceHarvester.strongest(documentText)
                .filter(com.marketai.document.identity.ExternalReference::isGloballyUnique)
                .orElse(null);

        String contentFingerprint = fingerprinter.fingerprint(pe);

        if (ref != null) {
            var priorByRef = fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(
                userId, ref.value(), ref.type().name());

            if (priorByRef.isPresent()) {
                var prior = priorByRef.get();

                // Same rail reference, different financial content. The rail issued that
                // identifier for exactly one payment, so two readings of it cannot both be
                // right — one of them is a restatement, a correction, or a parser error.
                //
                // Not importing is correct: booking it would double-count, and overwriting the
                // existing record would destroy already-verified financial history. But simply
                // returning, which is what happened before, leaves a genuine discrepancy
                // invisible. Record it against the original instead, for a human to resolve.
                if (!contentFingerprint.equals(prior.getFingerprint()) && !prior.isConflictDetected()) {
                    prior.setConflictDetected(true);
                    prior.setConflictDetail(truncateDetail(String.format(
                        "A later document quoted the same %s (%s) with different details: %s. "
                            + "The originally imported record was kept; confirm which is correct.",
                        ref.type(), ref.value(),
                        pe.getSourceDescription() == null ? "no description" : pe.getSourceDescription())));
                    fingerprintRepo.save(prior);
                    log.warn("Conflict on {} {} for user {} — same reference, different content",
                        ref.type(), ref.value(), userId);
                    return;
                }

                log.debug("Skipping transaction already imported under {} {}: {}",
                    ref.type(), ref.value(), pe.getSourceDescription());
                return;
            }
        }

        // --- Tier 2: uniform SHA-256 content gate, checked before any table-specific logic.
        // The same real-world transaction arriving again (resent alert, overlapping statement
        // PDF, a second parser matching the same email) hashes identically and is refused here,
        // so no individual import path can double-book by forgetting its own dedup check.
        String fp = contentFingerprint;
        if (fingerprintRepo.existsByUserIdAndFingerprint(userId, fp)) {
            log.debug("Skipping already-imported transaction (fingerprint {}): {}", fp, pe.getSourceDescription());
            return;
        }

        // --- Tier 3: weighted multi-factor match. Tiers 1/2 above only ever catch byte-for-byte
        // identical evidence; this catches the far more common case of the SAME transaction
        // described slightly differently by two documents (a transaction alert vs the statement
        // line that later restates it) — amount + near date + card + merchant, scored, not
        // hashed. See TransactionMatchScorer for the weighting and why no single signal here is
        // treated as decisive on its own (unlike the rail reference in tier 1).
        var bestMatch = matchScorer.findBestMatch(userId, pe);
        com.marketai.gmail.entity.DuplicateState duplicateState = com.marketai.gmail.entity.DuplicateState.NEW;
        Long matchedFingerprintId = null;
        Double matchConfidence = null;

        if (bestMatch.isPresent()) {
            var match = bestMatch.get();
            matchedFingerprintId = match.getMatched().getId();
            matchConfidence = match.getConfidence();

            if (match.getConfidence() >= TransactionMatchScorer.CONFIRM_THRESHOLD) {
                // High-confidence match: treated as corroboration of the existing record, not a
                // new financial event. A fingerprint row is still written (see below the
                // early-return branch is deliberately NOT taken here) so a re-delivery of this
                // exact email is caught by the cheap tier-2 hash check next time, instead of
                // re-running this scorer.
                fingerprintRepo.save(buildFingerprintRow(userId, pe, gmailMessageId, fp, ref,
                    com.marketai.gmail.entity.DuplicateState.MATCHED_TO_EXISTING, matchedFingerprintId, matchConfidence));
                log.info("MATCHED_TO_EXISTING (confidence {}): {} scored against fingerprint {} — not booked as a new transaction",
                    String.format("%.2f", match.getConfidence()), pe.getSourceDescription(), matchedFingerprintId);
                return;
            }

            // Below the confirm bar but above the review bar: per spec, an uncertain transaction
            // is never silently discarded. It IS imported — refusing to book a real transaction
            // just because it resembles another one would be its own kind of data loss — but the
            // fingerprint row is flagged NEEDS_REVIEW so a human/reconciliation pass can look at
            // it rather than the match being invisible.
            duplicateState = com.marketai.gmail.entity.DuplicateState.NEEDS_REVIEW;
            log.info("NEEDS_REVIEW (confidence {}): {} resembles fingerprint {} but below the auto-match threshold — importing and flagging",
                String.format("%.2f", match.getConfidence()), pe.getSourceDescription(), matchedFingerprintId);
        }

        routeImport(userId, user, pe, gmailMessageId);

        // Recorded only after a successful import, so a failed attempt stays retryable.
        fingerprintRepo.save(buildFingerprintRow(userId, pe, gmailMessageId, fp, ref,
            duplicateState, matchedFingerprintId, matchConfidence));
    }

    private com.marketai.gmail.entity.ImportedTransactionFingerprint buildFingerprintRow(
            Long userId, ParsedEmail pe, String gmailMessageId, String fp,
            com.marketai.document.identity.ExternalReference ref,
            com.marketai.gmail.entity.DuplicateState duplicateState, Long matchedFingerprintId, Double matchConfidence) {
        return com.marketai.gmail.entity.ImportedTransactionFingerprint.builder()
            .userId(userId)
            .fingerprint(fp)
            .type(pe.getType() != null ? pe.getType().name() : null)
            .gmailMessageId(gmailMessageId)
            .externalRef(ref != null ? ref.value() : null)
            .externalRefType(ref != null ? ref.type().name() : null)
            .description(pe.getSourceDescription() != null && pe.getSourceDescription().length() > 300
                ? pe.getSourceDescription().substring(0, 300) : pe.getSourceDescription())
            .amount(pe.getAmount())
            .transactionDate(TransactionMatchScorer.candidateDate(pe))
            .merchant(pe.getMerchant())
            .cardLast4(pe.getCardLast4())
            .duplicateState(duplicateState.name())
            .matchedFingerprintId(matchedFingerprintId)
            .matchConfidence(matchConfidence)
            .build();
    }

    /** Keeps conflict detail inside its column without truncating mid-character. */
    private static String truncateDetail(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    private void routeImport(Long userId, User user, ParsedEmail pe, String gmailMessageId) throws Exception {
        // Switching on a null enum throws NullPointerException. That a null type is reachable is
        // not hypothetical — the fingerprinter a few lines above this call explicitly handles
        // `getType() == null`, so such a ParsedEmail gets as far as here and then aborts the
        // whole message rather than skipping one unroutable item.
        if (pe.getType() == null) {
            log.warn("Skipping parsed item with no transaction type — nothing to route it to. Source: {}",
                pe.getSourceDescription());
            return;
        }

        switch (pe.getType()) {
            case TRADE_BUY:
            case TRADE_SELL:
                importTrade(userId, user, pe);
                break;
            case FD_OPEN:
                String fdBank = pe.getBank() != null ? pe.getBank() : "Bank";
                LocalDate fdStart = pe.getStartDate() != null ? pe.getStartDate() : LocalDate.now();
                if (pe.getPrincipal() != null && fdRepo.existsByUser_IdAndBankAndPrincipalAndStartDate(userId, fdBank, pe.getPrincipal(), fdStart)) {
                    log.debug("Skipping duplicate FD: {} ₹{} on {}", fdBank, pe.getPrincipal(), fdStart);
                    break;
                }
                // FdRequest carries @NotNull on principal, but bean validation only runs on the
                // controller's @Valid boundary — this path calls the service directly, so a null
                // principal would persist and then break every interest and maturity calculation
                // that reads it.
                if (pe.getPrincipal() == null || pe.getPrincipal().signum() <= 0) {
                    log.warn("REJECTED FD import — no usable principal (bank={}, principal={}). Source: {}",
                        fdBank, pe.getPrincipal(), pe.getSourceDescription());
                    break;
                }
                FdRequest fdReq = new FdRequest();
                fdReq.setBank(fdBank);
                fdReq.setPrincipal(pe.getPrincipal());
                fdReq.setRate(pe.getRate() != null ? pe.getRate() : BigDecimal.valueOf(7.0));
                fdReq.setCompounding(pe.getCompounding() != null ? pe.getCompounding() : "quarterly");
                fdReq.setAutoRenew(false);
                fdReq.setStartDate(fdStart);
                fdReq.setMaturityDate(pe.getMaturityDate());
                com.marketai.tracking.dto.FdResponse newFd = trackingService.addFd(userId, fdReq, user);
                // A bank "new FD opened" email is exactly what a renewal looks like from the
                // mailbox's point of view — there is no separate "renewed" notification to key
                // off, so every FD_OPEN is checked against the user's other FDs at the same
                // bank for a maturity/amount match. See TrackingService.detectAndLinkRenewal
                // for the tolerances; an unmatched FD is simply left as a fresh one.
                trackingService.detectAndLinkRenewal(userId, newFd.getId());
                break;
            case RD_OPEN:
                String rdBank = pe.getBank() != null ? pe.getBank() : "Bank";
                LocalDate rdStart = pe.getStartDate() != null ? pe.getStartDate() : LocalDate.now();
                if (pe.getMonthlyAmount() != null && rdRepo.existsByUser_IdAndBankAndMonthlyAmountAndStartDate(userId, rdBank, pe.getMonthlyAmount(), rdStart)) {
                    log.debug("Skipping duplicate RD: {} ₹{}/mo on {}", rdBank, pe.getMonthlyAmount(), rdStart);
                    break;
                }
                if (pe.getMonthlyAmount() == null || pe.getMonthlyAmount().signum() <= 0) {
                    log.warn("REJECTED RD import — no usable monthly amount (bank={}, amount={}). Source: {}",
                        rdBank, pe.getMonthlyAmount(), pe.getSourceDescription());
                    break;
                }
                RdRequest rdReq = new RdRequest();
                rdReq.setBank(rdBank);
                rdReq.setMonthlyAmount(pe.getMonthlyAmount());
                rdReq.setRate(pe.getRate() != null ? pe.getRate() : BigDecimal.valueOf(7.0));
                rdReq.setStartDate(rdStart);
                rdReq.setTenureMonths(pe.getTenureMonths() != null ? pe.getTenureMonths() : 12);
                trackingService.addRd(userId, rdReq, user);
                break;
            case MF_SIP:
            case MF_REDEEM:
                importMf(userId, user, pe);
                break;
            case DIVIDEND:
                importDividend(userId, pe, gmailMessageId);
                break;
            case INCOME:
                importIncome(userId, pe, gmailMessageId);
                break;
            case EXPENSE:
                importExpense(userId, pe, gmailMessageId);
                break;
            case CARD_BILL:
                applyCardBill(userId, pe, gmailMessageId);
                break;
            case CARD_PAYMENT:
                importCardPayment(userId, pe, gmailMessageId);
                break;
            default:
                break;
        }
    }

    private void importDividend(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String company = pe.getSymbol();
        String shortName = company != null ? company.replaceAll("(?i)\\s*(Limited|Ltd\\.?|Industries|Corporation)\\s*", " ").trim() : null;
        String desc = shortName != null ? shortName + " Dividend" : "Dividend";

        if (isDuplicateIncome(userId, pe.getAmount(), date, desc, gmailMessageId)) {
            log.debug("Skipping duplicate dividend: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .source(com.marketai.income.entity.IncomeSource.DIVIDEND)
            .incomeDate(date)
            .payer(company)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    private void importIncome(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String payer = pe.getMerchant();
        String source = pe.getIncomeSource() != null ? pe.getIncomeSource() : "Other";
        String desc = payer != null ? source + " from " + payer : source;
        if (pe.getSourceDescription() != null && pe.getSourceDescription().length() > desc.length()) {
            desc = pe.getSourceDescription();
        }

        if (isDuplicateIncome(userId, pe.getAmount(), date, desc, gmailMessageId)) {
            log.debug("Skipping duplicate income: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .source(com.marketai.income.entity.IncomeSource.fromLabel(source))
            .incomeDate(date)
            .payer(payer)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    private void importExpense(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String merchant = pe.getMerchant();
        String desc = merchant != null ? merchant
            : (pe.getCategory() != null ? pe.getCategory() + " spend" : "Expense");

        if (isDuplicateExpense(userId, pe.getAmount(), date, merchant, gmailMessageId)) {
            log.debug("Skipping duplicate expense: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        if (looksLikeRent(merchant, desc, pe.getCategory())) {
            // Rent has its own ledger (RentSchedule/Rent) so a schedule-generated "Upcoming"
            // placeholder and its real payment stay one row, not a placeholder plus a second
            // Expense row — see RentService.matchOrCreateFromGmail.
            rentService.matchOrCreateFromGmail(userId, pe.getAmount(), date, merchant, gmailMessageId);
            return;
        }
        expenseRepo.save(com.marketai.expense.entity.Expense.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .category(com.marketai.expense.entity.ExpenseCategory.fromLabel(pe.getCategory()))
            .expenseDate(date)
            .merchant(merchant)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    /** Rent detection is deliberately keyword-based, not a full classifier — a false negative
     *  just means the payment lands as an ordinary Expense (already correct behaviour today),
     *  so this only needs to catch the obvious cases without risking a false positive that
     *  would silently reroute an unrelated expense into the rent ledger. */
    private boolean looksLikeRent(String merchant, String description, String category) {
        return containsRent(merchant) || containsRent(description) || containsRent(category);
    }

    private boolean containsRent(String s) {
        return s != null && s.toLowerCase().contains("rent");
    }

    private boolean isDuplicateExpense(Long userId, BigDecimal amount, LocalDate date, String merchant, String gmailMessageId) {
        // Checked directly, not only via the same-day scan below. A parser that couldn't extract
        // a transaction date falls back to "today" (see importExpense), so the same email
        // re-synced on three different days previously produced three rows dated three different
        // days — each one outside the other two's same-day window, so the amount/merchant check
        // below never saw them and the gmailMessageId check on line below never ran against them.
        if (gmailMessageId != null && expenseRepo.existsByUserIdAndSourceEmailId(userId, gmailMessageId)) return true;
        if (amount == null || date == null) return false;
        List<com.marketai.expense.entity.Expense> existing =
            expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date);
        for (com.marketai.expense.entity.Expense e : existing) {
            if (e.getAmount().compareTo(amount) == 0) {
                if (merchant != null && merchant.equalsIgnoreCase(e.getMerchant())) return true;
                if (merchant != null && merchant.equalsIgnoreCase(e.getDescription())) return true;
            }
        }
        return false;
    }

    private boolean isDuplicateIncome(Long userId, BigDecimal amount, LocalDate date, String desc, String gmailMessageId) {
        // See isDuplicateExpense — same fix, same reason: a message-id match must not depend on
        // the fallback-dated row still being inside today's date window.
        if (gmailMessageId != null && incomeRepo.existsByUserIdAndSourceEmailId(userId, gmailMessageId)) return true;
        if (amount == null || date == null) return false;
        List<com.marketai.income.entity.Income> existing =
            incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, date, date);
        for (com.marketai.income.entity.Income i : existing) {
            if (i.getAmount().compareTo(amount) == 0 && desc != null && desc.equalsIgnoreCase(i.getDescription())) return true;
            if (i.getAmount().compareTo(amount) == 0 && com.marketai.income.entity.IncomeSource.DIVIDEND == i.getSource()
                    && desc != null && desc.toLowerCase().contains("dividend")) return true;
        }
        return false;
    }

    /**
     * A statement is booked as an immutable {@link com.marketai.card.entity.CardStatement} row
     * — never overwritten — so a later bill doesn't erase the history a
     * {@link com.marketai.card.service.CardReconciliationService} needs to explain a prior
     * cycle. {@code CreditCard.currentDue}/{@code currentDueDate} are still updated too, purely
     * as a cheap "what's due right now" cache for the existing card-list UI; the statement row,
     * not this cache, is the source of truth for reconciliation and history.
     */
    private void applyCardBill(Long userId, ParsedEmail pe, String gmailMessageId) {
        com.marketai.card.entity.CreditCard card = findCard(userId, pe);
        if (card == null) return; // no saved card to attach the bill to — the fingerprint is still recorded by the caller

        // CardStatement.totalDue is NOT NULL, and every other import path (FD, RD) already
        // refuses an unusable amount with a REJECTED log. Without the same guard here, a bill
        // whose amount regex missed — a new issuer template — failed the not-null constraint,
        // and because the transaction rolls back the fingerprint row too, the same message was
        // retried and failed identically on every subsequent sync: a permanent, self-repeating
        // sync failure instead of one skipped item.
        if (pe.getAmount() == null || pe.getAmount().signum() <= 0) {
            log.warn("REJECTED card bill import — no usable amount (card={}, amount={}). Source: {}",
                card.getName(), pe.getAmount(), pe.getSourceDescription());
            return;
        }

        // The document's own internal redundancy is the strongest available check on a
        // statement figure — far stronger than the extractor's own confidence, because a
        // misread digit almost always breaks the identity the statement itself asserts. Only
        // run it when every operand was actually found; a statement template that omits the
        // cycle debit/credit breakdown simply isn't checked, rather than treated as a failure.
        //
        // ArithmeticValidator.statementBalances(opening, credits, debits, closing) models a bank
        // account, where a credit INCREASES the balance and a debit DECREASES it. A credit card
        // is the opposite polarity: a purchase (cycleDebits) INCREASES what is owed, and a
        // payment (cycleCredits) DECREASES it. So the card's "purchases" fill the generic
        // method's "credits" slot and its "payments" fill the "debits" slot — previous +
        // purchases − payments = new due is the same identity, just relabelled for debt instead
        // of a balance.
        Boolean arithmeticMismatch = null;
        String arithmeticMismatchDetail = null;
        if (pe.getPreviousBalance() != null && pe.getCycleCredits() != null
                && pe.getCycleDebits() != null) {
            com.marketai.document.confidence.ArithmeticValidator.Check check =
                com.marketai.document.confidence.ArithmeticValidator.statementBalances(
                    pe.getPreviousBalance(), pe.getCycleDebits(), pe.getCycleCredits(), pe.getAmount());
            arithmeticMismatch = !check.passed();
            arithmeticMismatchDetail = check.detail();
            if (arithmeticMismatch) {
                log.warn("Card statement arithmetic mismatch for card {} (source {}): {}",
                    card.getName(), gmailMessageId, check.detail());
            }
        }

        cardStatementRepo.save(com.marketai.card.entity.CardStatement.builder()
            .cardId(card.getId())
            .userId(userId)
            .statementDate(pe.getStatementDate())
            .dueDate(pe.getDueDate())
            .totalDue(pe.getAmount())
            .minimumDue(pe.getMinimumDue())
            .previousBalance(pe.getPreviousBalance())
            .arithmeticMismatch(arithmeticMismatch)
            .arithmeticMismatchDetail(arithmeticMismatchDetail)
            .sourceEmailId(gmailMessageId)
            .build());

        // "What's due right now" must not regress when an older bill is backfilled. The statement
        // row above is the source of truth and keeps every cycle; this cache should only ever move
        // forward, so a statement older than the one already cached leaves it alone.
        boolean isNewerCycle = card.getCurrentDueDate() == null || pe.getDueDate() == null
            || !pe.getDueDate().isBefore(card.getCurrentDueDate());
        if (isNewerCycle) {
            card.setCurrentDue(pe.getAmount());
            card.setCurrentDueDate(pe.getDueDate());
            cardRepo.save(card);
        } else {
            log.debug("Backfilled older statement for card {} — leaving the current-due cache at {}",
                card.getName(), card.getCurrentDueDate());
        }
    }

    /**
     * A payment confirmation is booked as an immutable {@link com.marketai.card.entity.CardPayment}
     * fact. {@code cardId} is left null when no saved card matches — the row is not dropped,
     * just parked unreconciled, so a payment for a card the user hasn't added yet is not lost.
     * This never touches any balance directly: {@code CardReconciliationService} derives
     * outstanding/paid status by walking statements and payments together at read time.
     */
    private void importCardPayment(Long userId, ParsedEmail pe, String gmailMessageId) {
        com.marketai.card.entity.CreditCard card = findCard(userId, pe);
        com.marketai.card.entity.CardPaymentStatus status =
            "REVERSED".equalsIgnoreCase(pe.getPaymentStatus())
                ? com.marketai.card.entity.CardPaymentStatus.REVERSED
                : com.marketai.card.entity.CardPaymentStatus.CONFIRMED;

        cardPaymentRepo.save(com.marketai.card.entity.CardPayment.builder()
            .cardId(card != null ? card.getId() : null)
            .userId(userId)
            .amount(pe.getAmount())
            .paymentDate(pe.getPaymentDate() != null ? pe.getPaymentDate() : LocalDate.now())
            .referenceNumber(pe.getPaymentReference())
            .status(status)
            .sourceEmailId(gmailMessageId)
            .build());
    }

    private com.marketai.card.entity.CreditCard findCard(Long userId, ParsedEmail pe) {
        List<com.marketai.card.entity.CreditCard> matches = new ArrayList<>();
        if (pe.getCardLast4() != null) {
            matches = cardRepo.findByUserIdAndLastFour(userId, pe.getCardLast4());
        }
        if (matches.isEmpty() && pe.getBank() != null) {
            matches = cardRepo.findByUserIdAndIssuerIgnoreCase(userId, pe.getBank());
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private Portfolio getOrCreatePortfolio(Long userId, User user) throws Exception {
        List<Portfolio> portfolios = portfolioService.getUserPortfolios(userId);
        if (!portfolios.isEmpty()) return portfolios.get(0);
        return portfolioService.createPortfolio(userId, "My Portfolio", "Auto-created");
    }

    private void importTrade(Long userId, User user, ParsedEmail pe) throws Exception {
        // A trade is only a trade if it has a symbol, a quantity and a price. Each of these was
        // previously used unchecked:
        //
        //   - `pe.getSymbol() + ".NS"` on a null symbol produced the literal string "null.NS"
        //     and created a holding under it — a fabricated position that then took part in
        //     ledger replay like any other.
        //   - `BigDecimal.valueOf(pe.getQuantity())` unboxes an Integer, so a null quantity threw
        //     NullPointerException out of the middle of the import and abandoned the message.
        //   - `pe.getPrice()` was dereferenced further down the same path.
        //
        // Parsers return best-effort output from messy email; validating it here is what keeps
        // that best-effort quality from becoming a ledger entry.
        if (pe.getSymbol() == null || pe.getSymbol().isBlank()
                || pe.getQuantity() == null || pe.getQuantity() <= 0
                || pe.getPrice() == null || pe.getPrice().signum() <= 0) {
            log.warn("REJECTED trade import — incomplete transaction: symbol={}, quantity={}, price={}. "
                + "Refusing to create a holding from partial data. Source: {}",
                pe.getSymbol(), pe.getQuantity(), pe.getPrice(), pe.getSourceDescription());
            return;
        }

        Portfolio portfolio = getOrCreatePortfolio(userId, user);
        String exchange = pe.getExchange() != null ? pe.getExchange() : "NSE";
        String symbol = pe.getSymbol().trim() + ("BSE".equalsIgnoreCase(exchange) ? ".BO" : ".NS");
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        BigDecimal quantity = BigDecimal.valueOf(pe.getQuantity());

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, quantity, pe.getPrice())) {
            log.debug("Skipping duplicate trade: {} {} @ {} on {}", symbol, pe.getQuantity(), pe.getPrice(), date);
            return;
        }

        if (pe.getType() == ParsedEmail.Type.TRADE_SELL) {
            Long holdingId = portfolioService.findHoldingId(portfolio.getId(), symbol);
            if (holdingId != null) {
                portfolioService.sellHolding(portfolio.getId(), holdingId, userId,
                    quantity, pe.getPrice(), incomeRepo);
                log.info("Imported SELL: {} x {} @ ₹{}", symbol, quantity, pe.getPrice());
            } else {
                log.warn("SELL trade for {} but no holding found — recording as transaction only", symbol);
                importSellAsTransaction(portfolio.getId(), userId, symbol, pe, date, quantity);
            }
            return;
        }

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol(symbol);
        req.setName(pe.getSymbol());
        req.setQuantity(quantity);
        req.setPrice(pe.getPrice());
        req.setTransactionDate(date);
        req.setCharges(BigDecimal.ZERO);
        req.setBroker(pe.getExchange());
        req.setIsin(pe.getIsin());
        req.setDpId(pe.getDpId());
        req.setClientId(pe.getClientId());

        portfolioService.addHolding(portfolio.getId(), userId, req);
    }

    /**
     * Records an unmatchable sale as proceeds, not as a gain.
     *
     * <p>Nothing here knows the cost basis — that is the whole reason this branch exists — so the
     * row is booked under {@code UNMATCHED_SALE}. It used to be booked under {@code CAPITAL_GAIN},
     * which {@code TaxService} taxes directly: a ₹1,50,000 sale whose true gain was ₹8,000 added
     * ₹1,50,000 to the year's taxable gains and inflated the estimate by tens of thousands.
     */
    private void importSellAsTransaction(Long portfolioId, Long userId, String symbol,
            ParsedEmail pe, LocalDate date, BigDecimal quantity) {
        BigDecimal saleValue = pe.getPrice().multiply(quantity);
        String desc = "Sale of " + symbol.replace(".NS", "").replace(".BO", "");
        if (isDuplicateIncome(userId, saleValue, date, desc, null)) {
            log.debug("Skipping duplicate sell-as-income: {} on {} for ₹{}", desc, date, saleValue);
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(saleValue)
            .source(com.marketai.income.entity.IncomeSource.UNMATCHED_SALE)
            .incomeDate(date)
            .note("Sold " + quantity.stripTrailingZeros().toPlainString() + " units @ ₹" + pe.getPrice()
                + " — gross proceeds, no matching holding so the cost basis and therefore the gain are unknown")
            .build());
    }

    private void importMf(Long userId, User user, ParsedEmail pe) throws Exception {
        if (com.marketai.common.util.FinancialDataValidator.looksLikeUnverifiableFundName(pe.getFundName())) {
            log.warn("REJECTED MF import — fund name '{}' looks like mis-parsed header/boilerplate text, not a real scheme. " +
                "Source: {}. Refusing to create unverified holding.", pe.getFundName(), pe.getSourceDescription());
            return;
        }

        Portfolio portfolio = getOrCreatePortfolio(userId, user);

        // Units come from the document, or are derived as amount ÷ NAV. Both inputs must be
        // present *and* the NAV non-zero: a malformed email carrying "NAV: 0.00" would otherwise
        // throw ArithmeticException mid-import and abort the whole message.
        BigDecimal units = pe.getUnits();
        if (units == null && pe.getNav() != null && pe.getAmount() != null
                && pe.getNav().signum() > 0) {
            units = pe.getAmount().divide(pe.getNav(), 4, java.math.RoundingMode.HALF_UP);
        }

        // Previously this fell back to one unit priced at the whole transaction amount. The
        // total value came out right, which is why it looked harmless — but the quantity and
        // cost basis were both invented, and `recomputeFromLedger` replays them as fact. Mixing
        // a fabricated "1 unit @ ₹5,000" with real units produces a nonsense average cost for
        // the holding, and nothing downstream can tell which figure was made up.
        //
        // Refusing is consistent with the fund-name check above: this system does not create
        // unverified holdings.
        if (units == null || units.signum() <= 0) {
            log.warn("REJECTED MF import — cannot derive units for '{}': units={}, nav={}, amount={}. "
                + "Refusing to invent a unit count. Source: {}",
                pe.getFundName(), pe.getUnits(), pe.getNav(), pe.getAmount(), pe.getSourceDescription());
            return;
        }

        BigDecimal nav = pe.getNav() != null && pe.getNav().signum() > 0 ? pe.getNav() : null;
        if (nav == null && pe.getAmount() != null) {
            // Derive the per-unit price from the figures we do trust, rather than recording the
            // transaction amount in a field that means "price per unit".
            nav = pe.getAmount().divide(units, 4, java.math.RoundingMode.HALF_UP);
        }
        if (nav == null || nav.signum() <= 0) {
            log.warn("REJECTED MF import — cannot establish a per-unit price for '{}'. Source: {}",
                pe.getFundName(), pe.getSourceDescription());
            return;
        }

        String symbol;
        if (pe.getFundName() != null) {
            String clean = pe.getFundName().toUpperCase().replaceAll("[^A-Z0-9]", "");
            symbol = clean.substring(0, Math.min(30, clean.length())) + ".MF";
        } else {
            symbol = "MFSIP.MF";
        }
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, units, nav)) {
            log.debug("Skipping duplicate MF import: {} {} units @ {} on {}", symbol, units, nav, date);
            return;
        }

        // A redemption removes units. This branch used to be absent: MF_SIP and MF_REDEEM both
        // fell through to addHolding below, which writes a BUY — so a CAMS statement redeeming
        // 500 units of a 1,000-unit position left the holding reading 1,500 units with a cost
        // basis blended against the redemption NAV, added the withdrawn money to net worth
        // instead of removing it, and recorded no MfRedemption (so no STCG/LTCG at all).
        if (pe.getType() == ParsedEmail.Type.MF_REDEEM) {
            Long holdingId = portfolioService.findHoldingId(portfolio.getId(), symbol);
            if (holdingId == null) {
                // Refusing, not falling through: without the position there is no cost basis, and
                // the one thing that must never happen is a redemption being recorded as a purchase.
                log.warn("REJECTED MF redemption import — no holding found for '{}' ({} units @ ₹{}). "
                    + "Refusing to import a redemption as a purchase. Source: {}",
                    pe.getFundName(), units, nav, pe.getSourceDescription());
                return;
            }
            portfolioService.sellHolding(portfolio.getId(), holdingId, userId, units, nav, incomeRepo);
            log.info("Imported MF REDEMPTION: {} x {} units @ ₹{}", symbol, units, nav);
            return;
        }

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol(symbol);
        req.setName(pe.getFundName() != null ? pe.getFundName() : "Mutual Fund SIP");
        req.setQuantity(units);
        req.setPrice(nav != null ? nav : BigDecimal.ONE);
        req.setTransactionDate(date);
        req.setCharges(BigDecimal.ZERO);
        req.setBroker(pe.getProvider());
        req.setFolio(pe.getFolio());
        req.setIsin(pe.getIsin());

        portfolioService.addHolding(portfolio.getId(), userId, req);
    }
}
