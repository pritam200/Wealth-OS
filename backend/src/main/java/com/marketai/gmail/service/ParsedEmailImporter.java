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
     * <p>REQUIRES_NEW, not REQUIRED: PdfImportService calls this from inside its own transaction,
     * and under REQUIRED one rejected statement line marked that shared transaction rollback-only
     * — silently undoing every other line already booked from the same statement.
     *
     * @throws ImportRejectedException when the item cannot be booked as-is; nothing is written
     *         (no record, no fingerprint), so the caller can route it to review and a later
     *         retry is not blocked by a fingerprint for a transaction that was never booked.
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class,
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
        com.marketai.document.identity.ExternalReference ref = attributableReference(documentText);

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
        var priorByContent = fingerprintRepo.findFirstByUserIdAndFingerprint(userId, fp);
        if (priorByContent.isPresent()) {
            var prior = priorByContent.get();
            boolean sameDocument = gmailMessageId != null && gmailMessageId.equals(prior.getGmailMessageId());
            boolean provablyDistinct = ref != null && prior.getExternalRef() != null
                && !ref.value().equals(prior.getExternalRef());
            boolean spendOrCredit = TransactionMatchScorer.SCORED_TYPES.contains(pe.getType());

            if (pe.isUserConfirmed() && sameDocument) {
                // A person accepted an item this same email has already booked — say so rather
                // than reporting success while writing nothing.
                throw new ImportRejectedException("This email already recorded an identical transaction ("
                    + describe(prior) + "), so it was not added again.");
            }
            if (sameDocument || !(provablyDistinct || spendOrCredit)) {
                // Re-reading the same document, or an identical trade/fund/deposit record (a
                // contract note and its confirmation email) — the same transaction.
                log.debug("Skipping already-imported transaction (fingerprint {}): {}", fp, pe.getSourceDescription());
                return;
            }
            if (!provablyDistinct && !pe.isUserConfirmed()) {
                // Identical spending/credit from a different email: a resent alert, or a second
                // identical purchase (two ₹250 coffees at one café). Nothing in the data tells
                // them apart, so a person decides instead of the importer guessing.
                throw new ImportRejectedException("An identical transaction (" + describe(prior)
                    + ") was already recorded from another email — accept only if this is a separate one.");
            }
            // Known to be separate: different rail references, or a person confirmed it. Recorded
            // under a qualified fingerprint so the unique (user, fingerprint) key still holds and a
            // re-read of this document is still recognised.
            fp = fingerprinter.qualified(fp, provablyDistinct ? "ref:" + ref.value() : "msg:" + gmailMessageId);
            if (fingerprintRepo.existsByUserIdAndFingerprint(userId, fp)) {
                log.debug("Skipping already-imported transaction (fingerprint {}): {}", fp, pe.getSourceDescription());
                return;
            }
            if (provablyDistinct) {
                // Two rail references are proof, so the similarity holds below (which exist for the
                // case where nothing tells two payments apart) must not re-ask the question.
                pe = pe.toBuilder().userConfirmed(true).build();
            }
        }
        final boolean establishedSeparate = pe.isUserConfirmed() && !contentFingerprint.equals(fp);

        // --- Tier 3: weighted multi-factor match. Tiers 1/2 above only ever catch byte-for-byte
        // identical evidence; this catches the far more common case of the SAME transaction
        // described slightly differently by two documents (a transaction alert vs the statement
        // line that later restates it) — amount + near date + card + merchant, scored, not
        // hashed. See TransactionMatchScorer for the weighting and why no single signal here is
        // treated as decisive on its own (unlike the rail reference in tier 1).
        // A repeat line within one document (occurrence > 0) is a separate transaction by
        // definition, so corroboration-matching it against the first line would drop it.
        var bestMatch = pe.getOccurrenceInSource() > 0 || establishedSeparate
            ? java.util.Optional.<TransactionMatchScorer.ScoredMatch>empty()
            : matchScorer.findBestMatch(userId, pe, gmailMessageId);
        com.marketai.gmail.entity.DuplicateState duplicateState = com.marketai.gmail.entity.DuplicateState.NEW;
        Long matchedFingerprintId = null;
        Double matchConfidence = null;

        if (bestMatch.isPresent()) {
            var match = bestMatch.get();
            matchedFingerprintId = match.getMatched().getId();
            matchConfidence = match.getConfidence();

            if (match.getConfidence() >= TransactionMatchScorer.CONFIRM_THRESHOLD && pe.isUserConfirmed()) {
                throw new ImportRejectedException(String.format(
                    "This matches a transaction already recorded (%s, confidence %.2f), so it was not added again.",
                    match.getMatched().getDescription(), match.getConfidence()));
            }
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

    private static String describe(com.marketai.gmail.entity.ImportedTransactionFingerprint row) {
        String d = row.getDescription() != null ? row.getDescription()
            : (row.getMerchant() != null ? row.getMerchant() : "same details");
        return d + (row.getTransactionDate() != null ? ", " + row.getTransactionDate() : "");
    }

    /**
     * The rail reference that belongs to this transaction, or null when it can't be attributed.
     *
     * <p>References are harvested from the whole source text. That is only meaningful when the
     * text describes one payment: in a statement, "the strongest reference in the document" is
     * one line's UTR, and stamping it on every line made the importer treat line 2 onwards as
     * conflicting restatements of line 1 — and drop them. So a reference is used only when the
     * document carries exactly one distinct globally-unique reference; callers additionally pass
     * the text only for single-transaction sources.
     */
    private com.marketai.document.identity.ExternalReference attributableReference(String documentText) {
        if (documentText == null) return null;
        List<com.marketai.document.identity.ExternalReference> unique = referenceHarvester.harvest(documentText).stream()
            .filter(com.marketai.document.identity.ExternalReference::isGloballyUnique)
            .toList();
        long distinctValues = unique.stream().map(com.marketai.document.identity.ExternalReference::value).distinct().count();
        return distinctValues == 1 ? unique.get(0) : null;
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
            throw new ImportRejectedException("No transaction type could be determined for this item.");
        }

        switch (pe.getType()) {
            case TRADE_BUY:
            case TRADE_SELL:
                importTrade(userId, user, pe);
                break;
            case FD_OPEN:
                // Every field below feeds interest, maturity and renewal-linking maths, so none of
                // them is defaulted: a placeholder bank, a 7% rate or "today" as the start date
                // would book a deposit that looks real and is wrong in every derived figure.
                String fdBank = pe.getBank();
                LocalDate fdStart = pe.getStartDate();
                // FdRequest carries @NotNull on principal, but bean validation only runs on the
                // controller's @Valid boundary — this path calls the service directly, so a null
                // principal would persist and then break every interest and maturity calculation
                // that reads it.
                if (pe.getPrincipal() == null || pe.getPrincipal().signum() <= 0) {
                    throw new ImportRejectedException("Fixed deposit" + (fdBank != null ? " at " + fdBank : "")
                        + " has no usable principal amount.");
                }
                if (fdBank == null || fdBank.isBlank()) {
                    throw new ImportRejectedException("Fixed deposit of ₹" + pe.getPrincipal() + " does not name the bank.");
                }
                if (pe.getRate() == null || pe.getRate().signum() <= 0) {
                    throw new ImportRejectedException("Fixed deposit at " + fdBank + " does not state an interest rate.");
                }
                if (fdStart == null) {
                    throw new ImportRejectedException("Fixed deposit at " + fdBank + " does not state a start date.");
                }
                // Same-email re-sync is already stopped by the fingerprint gate, so an identical FD
                // here came from a different email. That is usually the same deposit described
                // twice — but people do open several identical FDs on one day, so it is a question
                // for the user, not something to guess either way. Repeat lines within one document
                // are distinct deposits by definition.
                if (!pe.isUserConfirmed() && pe.getOccurrenceInSource() == 0
                        && fdRepo.existsByUser_IdAndBankAndPrincipalAndStartDate(userId, fdBank, pe.getPrincipal(), fdStart)) {
                    throw new ImportRejectedException("A ₹" + pe.getPrincipal() + " FD at " + fdBank + " starting "
                        + fdStart + " is already recorded — accept only if this is a second, separate deposit.");
                }
                FdRequest fdReq = new FdRequest();
                fdReq.setBank(fdBank);
                fdReq.setPrincipal(pe.getPrincipal());
                fdReq.setRate(pe.getRate());
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
                // Same reasoning as FD_OPEN: nothing that drives the maturity maths is defaulted.
                String rdBank = pe.getBank();
                LocalDate rdStart = pe.getStartDate();
                if (pe.getMonthlyAmount() == null || pe.getMonthlyAmount().signum() <= 0) {
                    throw new ImportRejectedException("Recurring deposit" + (rdBank != null ? " at " + rdBank : "")
                        + " has no usable monthly amount.");
                }
                if (rdBank == null || rdBank.isBlank()) {
                    throw new ImportRejectedException("Recurring deposit of ₹" + pe.getMonthlyAmount() + "/month does not name the bank.");
                }
                if (pe.getRate() == null || pe.getRate().signum() <= 0) {
                    throw new ImportRejectedException("Recurring deposit at " + rdBank + " does not state an interest rate.");
                }
                if (rdStart == null) {
                    throw new ImportRejectedException("Recurring deposit at " + rdBank + " does not state a start date.");
                }
                if (pe.getTenureMonths() == null || pe.getTenureMonths() <= 0) {
                    throw new ImportRejectedException("Recurring deposit at " + rdBank + " does not state its tenure.");
                }
                if (!pe.isUserConfirmed() && pe.getOccurrenceInSource() == 0
                        && rdRepo.existsByUser_IdAndBankAndMonthlyAmountAndStartDate(userId, rdBank, pe.getMonthlyAmount(), rdStart)) {
                    throw new ImportRejectedException("A ₹" + pe.getMonthlyAmount() + "/month RD at " + rdBank + " starting "
                        + rdStart + " is already recorded — accept only if this is a second, separate deposit.");
                }
                RdRequest rdReq = new RdRequest();
                rdReq.setBank(rdBank);
                rdReq.setMonthlyAmount(pe.getMonthlyAmount());
                rdReq.setRate(pe.getRate());
                rdReq.setStartDate(rdStart);
                rdReq.setTenureMonths(pe.getTenureMonths());
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

    private void importDividend(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String company = pe.getSymbol();
        String shortName = company != null ? company.replaceAll("(?i)\\s*(Limited|Ltd\\.?|Industries|Corporation)\\s*", " ").trim() : null;
        String desc = shortName != null ? shortName + " Dividend" : "Dividend";

        if (isDuplicateIncome(userId, pe, date, desc, gmailMessageId)) {
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

    private void importIncome(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String payer = pe.getMerchant();
        String source = pe.getIncomeSource() != null ? pe.getIncomeSource() : "Other";
        String desc = payer != null ? source + " from " + payer : source;
        if (pe.getSourceDescription() != null && pe.getSourceDescription().length() > desc.length()) {
            desc = pe.getSourceDescription();
        }

        if (isDuplicateIncome(userId, pe, date, desc, gmailMessageId)) {
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

    private void importExpense(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String merchant = pe.getMerchant();
        String desc = merchant != null ? merchant
            : (pe.getCategory() != null ? pe.getCategory() + " spend" : "Expense");

        if (isDuplicateExpense(userId, pe, date, merchant, gmailMessageId)) {
            log.debug("Skipping duplicate expense: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        if (looksLikeRent(merchant, desc, pe.getCategory())) {
            // Rent has its own ledger (RentSchedule/Rent) so a schedule-generated "Upcoming"
            // placeholder and its real payment stay one row, not a placeholder plus a second
            // Expense row — see RentService.matchOrCreateFromGmail.
            rentService.matchOrCreateFromGmail(userId, pe.getAmount(), date, merchant, gmailMessageId,
                pe.getOccurrenceInSource());
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

    /**
     * Whether this exact line is already booked. Silent only when it provably is:
     *
     * <ul>
     *   <li><b>Same email:</b> rows this email already produced for the same amount and date are
     *       counted against the line's occurrence index (numbered on the same key). The 2nd identical line of a statement
     *       (index 1) is a duplicate only once two such rows exist — so identical lines are each
     *       booked once and a re-sync books none of them again. (This used to be "any row from
     *       this email exists", which dropped every line of a multi-line statement after the
     *       first.)</li>
     *   <li><b>Another email or a manual entry</b> with the same amount, day and merchant is not
     *       proof: two ₹250 coffees at the same café on one day are two transactions. That case
     *       goes to review with the reason instead of being silently dropped.</li>
     * </ul>
     */
    private boolean isDuplicateExpense(Long userId, ParsedEmail pe, LocalDate date, String merchant,
                                       String gmailMessageId) throws ImportRejectedException {
        BigDecimal amount = pe.getAmount();
        if (amount == null) throw new ImportRejectedException("No amount could be determined for this expense.");
        // Rows this email already produced with this amount and date — expenses and the rent rows
        // that rent-like expenses are routed to — against the line's occurrence index, which
        // EmailLLMParserService numbers on exactly the same key (debit, amount, date).
        long sameEmailSameLine = gmailMessageId == null ? 0
            : rentService.countFromEmail(userId, gmailMessageId, amount, date);
        com.marketai.expense.entity.Expense otherSource = null;
        for (com.marketai.expense.entity.Expense e :
                expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date)) {
            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) continue;
            if (gmailMessageId != null && gmailMessageId.equals(e.getSourceEmailId())) {
                sameEmailSameLine++;
            } else if (otherSource == null && merchant != null
                    && (merchant.equalsIgnoreCase(e.getMerchant()) || merchant.equalsIgnoreCase(e.getDescription()))) {
                otherSource = e;
            }
        }
        if (sameEmailSameLine > pe.getOccurrenceInSource()) return true;
        if (otherSource != null && !pe.isUserConfirmed()) {
            throw new ImportRejectedException("A ₹" + amount + " expense at " + merchant + " on " + date
                + " is already recorded from another source — accept only if this is a separate payment.");
        }
        return false;
    }

    /** Same rules as {@link #isDuplicateExpense}. */
    private boolean isDuplicateIncome(Long userId, ParsedEmail pe, LocalDate date, String desc,
                                      String gmailMessageId) throws ImportRejectedException {
        BigDecimal amount = pe.getAmount();
        if (amount == null) throw new ImportRejectedException("No amount could be determined for this credit.");
        int sameEmailSameLine = 0;
        com.marketai.income.entity.Income otherSource = null;
        for (com.marketai.income.entity.Income i :
                incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, date, date)) {
            if (i.getAmount() == null || i.getAmount().compareTo(amount) != 0) continue;
            if (gmailMessageId != null && gmailMessageId.equals(i.getSourceEmailId())) {
                sameEmailSameLine++;
                continue;
            }
            boolean sameDesc = desc != null && desc.equalsIgnoreCase(i.getDescription());
            boolean bothDividends = com.marketai.income.entity.IncomeSource.DIVIDEND == i.getSource()
                && desc != null && desc.toLowerCase().contains("dividend");
            if ((sameDesc || bothDividends) && otherSource == null) otherSource = i;
        }
        if (sameEmailSameLine > pe.getOccurrenceInSource()) return true;
        if (otherSource != null && !pe.isUserConfirmed()) {
            throw new ImportRejectedException("A ₹" + amount + " credit (" + otherSource.getDescription() + ") on " + date
                + " is already recorded from another source — accept only if this is a separate credit.");
        }
        return false;
    }

    /** The transaction's own date. Never "today": a guessed date books the item into the wrong
     *  month and defeats every date-based duplicate check. */
    private static LocalDate requireDate(ParsedEmail pe) throws ImportRejectedException {
        if (pe.getTradeDate() == null) {
            throw new ImportRejectedException("No transaction date could be determined for this item.");
        }
        return pe.getTradeDate();
    }

    /**
     * A statement is booked as an immutable {@link com.marketai.card.entity.CardStatement} row
     * — never overwritten — so a later bill doesn't erase the history a
     * {@link com.marketai.card.service.CardReconciliationService} needs to explain a prior
     * cycle. {@code CreditCard.currentDue}/{@code currentDueDate} are still updated too, purely
     * as a cheap "what's due right now" cache for the existing card-list UI; the statement row,
     * not this cache, is the source of truth for reconciliation and history.
     */
    private void applyCardBill(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        com.marketai.card.entity.CreditCard card = findCard(userId, pe);
        // Previously returned quietly — and the caller then wrote the fingerprint, so the bill was
        // marked imported and never retried even after the card was added. Now nothing is written
        // and the item waits in review, where accepting it after adding the card books it.
        if (card == null) {
            throw new ImportRejectedException("No saved credit card matches this bill"
                + (pe.getCardLast4() != null ? " (card ending " + pe.getCardLast4() + ")" : "")
                + (pe.getBank() != null ? " from " + pe.getBank() : "")
                + " — add the card, or several cards from this issuer need the last 4 digits to tell them apart.");
        }

        // CardStatement.totalDue is NOT NULL, and every other import path (FD, RD) already
        // refuses an unusable amount with a REJECTED log. Without the same guard here, a bill
        // whose amount regex missed — a new issuer template — failed the not-null constraint,
        // and because the transaction rolls back the fingerprint row too, the same message was
        // retried and failed identically on every subsequent sync: a permanent, self-repeating
        // sync failure instead of one skipped item.
        if (pe.getAmount() == null || pe.getAmount().signum() <= 0) {
            throw new ImportRejectedException("Credit card bill for " + card.getName() + " has no usable amount due.");
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
        // With two cards from one issuer and no last-4 to tell them apart, picking the first
        // would attach the bill or payment to whichever card happened to be saved first.
        return matches.size() == 1 ? matches.get(0) : null;
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
            throw new ImportRejectedException("Trade is incomplete (symbol=" + pe.getSymbol()
                + ", quantity=" + pe.getQuantity() + ", price=" + pe.getPrice()
                + ") — not creating a holding from partial data.");
        }

        Portfolio portfolio = getOrCreatePortfolio(userId, user);
        String exchange = pe.getExchange() != null ? pe.getExchange() : "NSE";
        String symbol = pe.getSymbol().trim() + ("BSE".equalsIgnoreCase(exchange) ? ".BO" : ".NS");
        LocalDate date = requireDate(pe);
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
            ParsedEmail pe, LocalDate date, BigDecimal quantity) throws ImportRejectedException {
        BigDecimal saleValue = pe.getPrice().multiply(quantity);
        String desc = "Sale of " + symbol.replace(".NS", "").replace(".BO", "");
        if (isDuplicateIncome(userId, pe.toBuilder().amount(saleValue).build(), date, desc, null)) {
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
            throw new ImportRejectedException("Fund name '" + pe.getFundName()
                + "' does not look like a real scheme name — please confirm the fund.");
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
            throw new ImportRejectedException("Units for '" + pe.getFundName()
                + "' could not be determined (units=" + pe.getUnits() + ", nav=" + pe.getNav()
                + ", amount=" + pe.getAmount() + ").");
        }

        BigDecimal nav = pe.getNav() != null && pe.getNav().signum() > 0 ? pe.getNav() : null;
        if (nav == null && pe.getAmount() != null) {
            // Derive the per-unit price from the figures we do trust, rather than recording the
            // transaction amount in a field that means "price per unit".
            nav = pe.getAmount().divide(units, 4, java.math.RoundingMode.HALF_UP);
        }
        if (nav == null || nav.signum() <= 0) {
            throw new ImportRejectedException("NAV for '" + pe.getFundName() + "' could not be determined.");
        }

        String symbol;
        if (pe.getFundName() != null) {
            String clean = pe.getFundName().toUpperCase().replaceAll("[^A-Z0-9]", "");
            symbol = clean.substring(0, Math.min(30, clean.length())) + ".MF";
        } else {
            symbol = "MFSIP.MF";
        }
        LocalDate date = requireDate(pe);

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
                throw new ImportRejectedException("Redemption of " + units.stripTrailingZeros().toPlainString()
                    + " units of '" + pe.getFundName() + "' has no matching holding — import the purchase history first.");
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
