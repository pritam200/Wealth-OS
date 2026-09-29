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

    /** What happened to one extracted item. Anything that can't be booked safely throws
     *  {@link ImportRejectedException} instead, so it reaches the review queue. */
    public enum ImportOutcome {
        /** A new record was written. */
        IMPORTED,
        /** Already recorded — the same email re-read, or the same transaction from another source. */
        DUPLICATE,
        /** Carries the same payment reference as a recorded transaction but different details;
         *  the original was kept and the discrepancy flagged on it. */
        CONFLICT
    }

    public ImportOutcome importParsedEmail(Long userId, User user, ParsedEmail pe) throws Exception {
        return importParsedEmail(userId, user, pe, null);
    }

    public ImportOutcome importParsedEmail(Long userId, User user, ParsedEmail pe, String gmailMessageId) throws Exception {
        return importParsedEmail(userId, user, pe, gmailMessageId, null);
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
    public ImportOutcome importParsedEmail(Long userId, User user, ParsedEmail pe, String gmailMessageId,
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
                    return ImportOutcome.CONFLICT;
                }

                log.debug("Skipping transaction already imported under {} {}: {}",
                    ref.type(), ref.value(), pe.getSourceDescription());
                return ImportOutcome.DUPLICATE;
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
            // Cash movements that can legitimately repeat (two equal refunds, two equal transfers,
            // equal interest from two deposits) go to review, never silently dropped.
            boolean spendOrCredit = TransactionMatchScorer.SCORED_TYPES.contains(pe.getType())
                || REPEATABLE_CASH_TYPES.contains(pe.getType());

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
                return ImportOutcome.DUPLICATE;
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
                return ImportOutcome.DUPLICATE;
            }
            if (provablyDistinct) {
                // Two rail references are proof, so the similarity holds below (which exist for the
                // case where nothing tells two payments apart) must not re-ask the question.
                pe = pe.toBuilder().userConfirmed(true).build();
            }
        }
        final boolean establishedSeparate = pe.isUserConfirmed() && !contentFingerprint.equals(fp);

        // --- A different model or prompt re-reading an email that already booked lines. An
        // identical line stopped at tier 2 above; one that differs may be a line the earlier read
        // missed, or the same line read differently (₹1,200 read as ₹1,250) — nothing tells them
        // apart, and booking it could double-count. A person compares it instead; what the
        // earlier read booked is never changed.
        if (gmailMessageId != null && !pe.isUserConfirmed() && pe.getExtractionVersion() != null && pe.getReadStartedAt() != null
                && fingerprintRepo.existsReadByOtherVersion(userId, gmailMessageId, pe.getExtractionVersion(), pe.getReadStartedAt())) {
            throw new ImportRejectedException("This email was read before by a different model or prompt, and this "
                + "line differs from what that read recorded. Check it against the existing records before accepting.");
        }

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
                return ImportOutcome.DUPLICATE;
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

        com.marketai.common.ledger.Provenance provenance = com.marketai.common.ledger.Provenance.builder()
            .sourceEmailId(gmailMessageId).sourceFingerprint(fp)
            .extractionMethod(pe.getExtractionMethod()).extractionConfidence(pe.getExtractionConfidence())
            .sourceReference(truncate(pe.getTradeReference(), 60))
            .linkGroup(linkGroup(pe, gmailMessageId))
            .build();
        boolean booked = routeImport(userId, user, pe, gmailMessageId, provenance);

        // Recorded only after a successful import, so a failed attempt stays retryable. A
        // duplicate the domain checks caught still gets one, so the next re-read stops at tier 2.
        fingerprintRepo.save(buildFingerprintRow(userId, pe, gmailMessageId, fp, ref,
            booked ? duplicateState : com.marketai.gmail.entity.DuplicateState.MATCHED_TO_EXISTING,
            matchedFingerprintId, matchConfidence));
        return booked ? ImportOutcome.IMPORTED : ImportOutcome.DUPLICATE;
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
            .attachmentId(pe.getSourceAttachmentId())
            .documentHash(pe.getSourceDocumentHash())
            .extractionMethod(pe.getExtractionMethod())
            .extractionConfidence(pe.getExtractionConfidence())
            .extractionVersion(truncate(pe.getExtractionVersion(), 160))
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

    /**
     * The two legs of a switch come from one email, so the email identifies the pair: the
     * redemption leg is then known to have been reinvested, not paid out to the bank.
     */
    static String linkGroup(ParsedEmail pe, String gmailMessageId) {
        if (pe.getLinkGroup() == null) return null;
        String g = pe.getLinkGroup() + (gmailMessageId != null ? ":" + gmailMessageId : "");
        return truncate(g, 100);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** Keeps conflict detail inside its column without truncating mid-character. */
    private static String truncateDetail(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    /** @return false when the item turned out to be a duplicate of an existing record. */
    private boolean routeImport(Long userId, User user, ParsedEmail pe, String gmailMessageId,
                                com.marketai.common.ledger.Provenance provenance) throws Exception {
        String lineKey = provenance.getSourceFingerprint();
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
                return importTrade(userId, user, pe, provenance);
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
                fdReq.setProvenance(provenance);
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
                rdReq.setProvenance(provenance);
                trackingService.addRd(userId, rdReq, user);
                break;
            case MF_SIP:
            case MF_REDEEM:
                return importMf(userId, user, pe, provenance);
            case DIVIDEND:
                return importDividend(userId, pe, gmailMessageId, lineKey);
            case INCOME:
                return importIncome(userId, pe, gmailMessageId, lineKey);
            case EXPENSE:
                return importExpense(userId, pe, gmailMessageId, lineKey);
            case CARD_BILL:
                applyCardBill(userId, pe, gmailMessageId);
                break;
            case CARD_PAYMENT:
                importCardPayment(userId, pe, gmailMessageId);
                break;
            case CORPORATE_ACTION:
                return importCorporateAction(userId, user, pe, provenance);
            case DEPOSIT_CLOSE:
                return closeDeposit(userId, pe, gmailMessageId);
            case DEPOSIT_INTEREST:
                return importDepositInterest(userId, pe, gmailMessageId, lineKey);
            case REFUND:
                return importRefund(userId, pe, gmailMessageId, lineKey);
            case OWN_TRANSFER:
                return importOwnTransfer(userId, pe, gmailMessageId, lineKey);
            default:
                break;
        }
        return true;
    }

    private boolean importDividend(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String company = pe.getSymbol();
        String shortName = company != null ? company.replaceAll("(?i)\\s*(Limited|Ltd\\.?|Industries|Corporation)\\s*", " ").trim() : null;
        String desc = shortName != null ? shortName + " Dividend" : "Dividend";

        // The amount is what was credited; the dividend declared is that plus the TDS withheld.
        BigDecimal tds = pe.getTds() != null && pe.getTds().signum() > 0 ? pe.getTds() : null;
        // Compared on the gross, which is what is stored.
        if (isDuplicateIncome(userId, tds == null ? pe : pe.toBuilder().amount(pe.getAmount().add(tds)).build(),
                date, desc, gmailMessageId, lineKey)) {
            log.debug("Skipping duplicate dividend: {} on {} for ₹{}", desc, date, pe.getAmount());
            return false;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(tds == null ? pe.getAmount() : pe.getAmount().add(tds))
            .tds(tds)
            .source(com.marketai.income.entity.IncomeSource.DIVIDEND)
            .incomeDate(date)
            .payer(company)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .sourceFingerprint(lineKey)
            .note("Auto-imported from email")
            .build());
        return true;
    }

    private boolean importIncome(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String payer = pe.getMerchant();
        String source = pe.getIncomeSource() != null ? pe.getIncomeSource() : "Other";
        String desc = payer != null ? source + " from " + payer : source;
        if (pe.getSourceDescription() != null && pe.getSourceDescription().length() > desc.length()) {
            desc = pe.getSourceDescription();
        }

        if (isDuplicateIncome(userId, pe, date, desc, gmailMessageId, lineKey)) {
            log.debug("Skipping duplicate income: {} on {} for ₹{}", desc, date, pe.getAmount());
            return false;
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
            .sourceFingerprint(lineKey)
            .note("Auto-imported from email")
            .build());
        return true;
    }

    private boolean importExpense(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String merchant = pe.getMerchant();
        String desc = merchant != null ? merchant
            : (pe.getCategory() != null ? pe.getCategory() + " spend" : "Expense");

        if (isDuplicateExpense(userId, pe, date, merchant, desc, gmailMessageId, lineKey)) {
            log.debug("Skipping duplicate expense: {} on {} for ₹{}", desc, date, pe.getAmount());
            return false;
        }
        if (looksLikeRent(merchant, desc, pe.getCategory())) {
            // Rent has its own ledger (RentSchedule/Rent) so a schedule-generated "Upcoming"
            // placeholder and its real payment stay one row, not a placeholder plus a second
            // Expense row — see RentService.matchOrCreateFromGmail.
            rentService.matchOrCreateFromGmail(userId, pe.getAmount(), date, merchant, gmailMessageId,
                pe.getOccurrenceInSource());
            return true;
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
            .sourceFingerprint(lineKey)
            .note("Auto-imported from email")
            .build());
        return true;
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
    private boolean isDuplicateExpense(Long userId, ParsedEmail pe, LocalDate date, String merchant, String desc,
                                       String gmailMessageId, String lineKey) throws ImportRejectedException {
        BigDecimal amount = pe.getAmount();
        if (amount == null) throw new ImportRejectedException("No amount could be determined for this expense.");
        // Rows this email already produced with this amount and date — expenses and the rent rows
        // that rent-like expenses are routed to — against the line's occurrence index, which
        // EmailLLMParserService numbers on exactly the same key (debit, amount, date).
        long sameEmailSameLine = gmailMessageId == null ? 0
            : rentService.countFromEmail(userId, gmailMessageId, amount, date);
        com.marketai.expense.entity.Expense otherSource = null;
        com.marketai.expense.entity.Expense enteredByHand = null;
        String[] mine = {merchant, desc};
        for (com.marketai.expense.entity.Expense e :
                expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date)) {
            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) continue;
            if (gmailMessageId != null && gmailMessageId.equals(e.getSourceEmailId())) {
                // Amount and date alone do not make two lines one payment: a clearly different
                // payee in the same email (its body and its statement) is a different one.
                if (!differentParty(merchant, e.getMerchant())) sameEmailSameLine++;
            } else if (com.marketai.common.ledger.PartyNames.anySame(mine, new String[]{e.getMerchant(), e.getDescription()})) {
                if (e.getSourceEmailId() == null) {
                    if (enteredByHand == null) enteredByHand = e;
                } else if (otherSource == null) {
                    otherSource = e;
                }
            }
        }
        if (sameEmailSameLine > pe.getOccurrenceInSource()) return true;
        if (enteredByHand != null && gmailMessageId != null && !pe.isUserConfirmed()) {
            // The user recorded this payment by hand before the email arrived: the email is the
            // evidence for that row, not a second payment. Linked, so the next hand-entered
            // twin is not matched to the same email again.
            enteredByHand.setSourceEmailId(gmailMessageId);
            enteredByHand.setSourceFingerprint(lineKey);
            expenseRepo.save(enteredByHand);
            log.info("Linked email {} to hand-entered expense {}", gmailMessageId, enteredByHand.getId());
            return true;
        }
        if (enteredByHand != null && otherSource == null) otherSource = enteredByHand;
        if (otherSource != null && !pe.isUserConfirmed()) {
            throw new ImportRejectedException("A ₹" + amount + " expense (" + (merchant != null ? merchant : desc) + ") on " + date
                + " is already recorded from another source — accept only if this is a separate payment.");
        }
        return false;
    }

    /** Both name a counterparty, and not the same one. Unknown on either side is never "different". */
    static boolean differentParty(String a, String b) {
        return a != null && !a.isBlank() && b != null && !b.isBlank()
            && !com.marketai.common.ledger.PartyNames.sameParty(a, b);
    }

    /** Same rules as {@link #isDuplicateExpense}. */
    private boolean isDuplicateIncome(Long userId, ParsedEmail pe, LocalDate date, String desc,
                                      String gmailMessageId, String lineKey) throws ImportRejectedException {
        BigDecimal amount = pe.getAmount();
        if (amount == null) throw new ImportRejectedException("No amount could be determined for this credit.");
        int sameEmailSameLine = 0;
        com.marketai.income.entity.Income otherSource = null;
        com.marketai.income.entity.Income enteredByHand = null;
        String[] mine = {desc, pe.getMerchant(), pe.getSymbol()};
        for (com.marketai.income.entity.Income i :
                incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, date, date)) {
            if (i.getAmount() == null || i.getAmount().compareTo(amount) != 0) continue;
            if (gmailMessageId != null && gmailMessageId.equals(i.getSourceEmailId())) {
                // The payer is stored as the company for a dividend, the counterparty otherwise.
                String myPayer = pe.getType() == ParsedEmail.Type.DIVIDEND ? pe.getSymbol() : pe.getMerchant();
                if (!differentParty(myPayer, i.getPayer())) sameEmailSameLine++;
                continue;
            }
            boolean sameParty = com.marketai.common.ledger.PartyNames.anySame(mine,
                new String[]{i.getDescription(), i.getPayer()});
            boolean bothDividends = com.marketai.income.entity.IncomeSource.DIVIDEND == i.getSource()
                && desc != null && desc.toLowerCase().contains("dividend");
            if (!(sameParty || bothDividends)) continue;
            if (i.getSourceEmailId() == null) {
                if (enteredByHand == null) enteredByHand = i;
            } else if (otherSource == null) {
                otherSource = i;
            }
        }
        if (sameEmailSameLine > pe.getOccurrenceInSource()) return true;
        if (enteredByHand != null && gmailMessageId != null && !pe.isUserConfirmed()) {
            // See isDuplicateExpense: the email is the evidence for the hand-entered row.
            enteredByHand.setSourceEmailId(gmailMessageId);
            enteredByHand.setSourceFingerprint(lineKey);
            incomeRepo.save(enteredByHand);
            log.info("Linked email {} to hand-entered income {}", gmailMessageId, enteredByHand.getId());
            return true;
        }
        if (enteredByHand != null && otherSource == null) otherSource = enteredByHand;
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
    private void importCardPayment(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        // Never "today": a payment booked on the day it was imported lands in the wrong card
        // cycle and can't be matched against the statement it settled.
        LocalDate paidOn = pe.getPaymentDate() != null ? pe.getPaymentDate() : pe.getTradeDate();
        if (paidOn == null) {
            throw new ImportRejectedException("The card payment's date isn't stated — confirm the date to record it.");
        }
        com.marketai.card.entity.CreditCard card = findCard(userId, pe);
        com.marketai.card.entity.CardPaymentStatus status =
            "REVERSED".equalsIgnoreCase(pe.getPaymentStatus())
                ? com.marketai.card.entity.CardPaymentStatus.REVERSED
                : com.marketai.card.entity.CardPaymentStatus.CONFIRMED;

        cardPaymentRepo.save(com.marketai.card.entity.CardPayment.builder()
            .cardId(card != null ? card.getId() : null)
            .userId(userId)
            .amount(pe.getAmount())
            .paymentDate(paidOn)
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

    /* ── Corporate actions ───────────────────────────────────── */

    private boolean importCorporateAction(Long userId, User user, ParsedEmail pe,
                                          com.marketai.common.ledger.Provenance provenance) throws Exception {
        String action = pe.getCorporateAction();
        LocalDate date = requireDate(pe);
        Portfolio portfolio = getOrCreatePortfolio(userId, user);
        String symbol = equitySymbol(pe.getSymbol(), pe.getExchange());
        PortfolioService.CorporateActionResult result;
        try {
            result = switch (action == null ? "" : action) {
                case "SPLIT" -> portfolioService.recordSplit(portfolio.getId(), symbol, pe.getRatioFrom(), pe.getRatioTo(), date, provenance);
                case "BONUS" -> portfolioService.recordBonus(portfolio.getId(), symbol, pe.getRatioFrom(), pe.getRatioTo(),
                    pe.getUnits(), date, provenance);
                case "MERGER" -> portfolioService.recordMerger(portfolio.getId(), symbol,
                    equitySymbol(pe.getNewSymbol(), pe.getExchange()), pe.getRatioFrom(), pe.getRatioTo(), date, provenance);
                default -> throw new ImportRejectedException("The corporate action (" + action + ") could not be recorded automatically.");
            };
        } catch (IllegalArgumentException e) {
            throw new ImportRejectedException(e.getMessage());
        }
        log.info("Corporate action for user {}: {}", userId, result.detail());
        return result.booked();
    }

    private static String equitySymbol(String raw, String exchange) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase();
        if (s.endsWith(".NS") || s.endsWith(".BO")) return s;
        return s + ("BSE".equalsIgnoreCase(exchange) ? ".BO" : ".NS");
    }

    /* ── Deposits ────────────────────────────────────────────── */

    /**
     * A maturity or premature-closure payout closes the one open deposit it belongs to, on the
     * bank's figures. It never creates a record of its own: the principal was already counted
     * while the deposit was open, and only the excess paid out is interest.
     */
    private boolean closeDeposit(Long userId, ParsedEmail pe, String gmailMessageId) throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        BigDecimal paid = pe.getAmount();
        if (paid == null || paid.signum() <= 0) {
            throw new ImportRejectedException("The deposit payout amount could not be read.");
        }
        boolean rd = "RD".equalsIgnoreCase(pe.getInstrumentKind());
        String bank = pe.getBank();
        String what = (rd ? "RD" : "FD") + " payout of ₹" + paid.toPlainString() + (bank != null ? " from " + bank : "");
        if (rd) {
            List<com.marketai.tracking.entity.RecurringDeposit> open = new ArrayList<>();
            boolean closedOnTheseFigures = false;
            for (var d : rdRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
                if (!com.marketai.common.ledger.PartyNames.sameParty(bank, d.getBank())) continue;
                if (isClosed(d.getStatus())) {
                    closedOnTheseFigures |= sameClosure(d.getMaturityAmount(), d.getClosedDate(), paid, date);
                    continue;
                }
                open.add(d);
            }
            // Only when nothing open could take it: a second identical deposit at the same bank
            // must be closed, not mistaken for the first one's payout.
            if (open.isEmpty() && closedOnTheseFigures) return false;
            var target = single(open, what, "recurring deposit");
            closeOrDuplicate(() -> trackingService.closeRd(target.getId(), userId, paid, pe.getTds(), date, gmailMessageId));
            return true;
        }
        List<com.marketai.tracking.entity.FixedDeposit> open = new ArrayList<>();
        boolean closedOnTheseFigures = false;
        for (var d : fdRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!com.marketai.common.ledger.PartyNames.sameParty(bank, d.getBank())) continue;
            if (isClosed(d.getStatus())) {
                boolean principalFits = pe.getPrincipal() == null || d.getPrincipal() == null || d.getPrincipal().compareTo(pe.getPrincipal()) == 0;
                closedOnTheseFigures |= principalFits && sameClosure(d.getMaturityAmount(), d.getClosedDate(), paid, date);
                continue;
            }
            // The principal, when the advice states it, tells two deposits at one bank apart;
            // a payout smaller than the principal can't belong to that deposit.
            if (pe.getPrincipal() != null && d.getPrincipal() != null && d.getPrincipal().compareTo(pe.getPrincipal()) != 0) continue;
            if (pe.getPrincipal() == null && d.getPrincipal() != null && paid.add(nz(pe.getTds())).compareTo(d.getPrincipal()) < 0) continue;
            open.add(d);
        }
        if (open.size() > 1 && pe.getPrincipal() == null) {
            // Several open deposits at the bank: the one due soonest before the payout is the
            // likeliest, but that is a guess — only an exact maturity-date match is taken.
            List<com.marketai.tracking.entity.FixedDeposit> onDate = open.stream()
                .filter(d -> date.equals(d.getMaturityDate())).toList();
            if (onDate.size() == 1) open = onDate;
        }
        if (open.isEmpty() && closedOnTheseFigures) return false;
        var target = single(open, what, "fixed deposit");
        closeOrDuplicate(() -> trackingService.closeFd(target.getId(), userId, paid, pe.getTds(), date, gmailMessageId));
        return true;
    }

    /** A deposit already closed with this payout on this day: the advice has been recorded. */
    private static boolean sameClosure(BigDecimal closedAmount, LocalDate closedOn, BigDecimal paid, LocalDate date) {
        return closedAmount != null && closedAmount.compareTo(paid) == 0 && (closedOn == null || closedOn.equals(date));
    }

    private static boolean isClosed(String status) {
        return "CLOSED".equalsIgnoreCase(status) || "MATURED_RENEWED".equalsIgnoreCase(status);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static <T> T single(List<T> candidates, String what, String kind) throws ImportRejectedException {
        if (candidates.isEmpty()) {
            throw new ImportRejectedException(what + " — no open " + kind + " at that bank matches it. "
                + "Add the deposit (or close it by hand) so the principal and interest are split correctly.");
        }
        if (candidates.size() > 1) {
            throw new ImportRejectedException(what + " — " + candidates.size() + " open " + kind
                + "s at that bank could be the one that paid out; close the right one by hand.");
        }
        return candidates.get(0);
    }

    private interface Closer { void run(); }

    /** Already closed (by hand, or by a renewal) means its interest is already booked. */
    private static void closeOrDuplicate(Closer c) throws ImportRejectedException {
        try {
            c.run();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw new ImportRejectedException(e.getReason() != null ? e.getReason() : "The deposit could not be closed.");
        }
    }

    /**
     * Interest paid out by a deposit during its term, or TDS withheld from it. Booked gross, with
     * the TDS beside it: the TDS is tax already paid, not money that vanished. A TDS-only advice
     * is attached to the interest it was withheld from when that is on record.
     */
    private boolean importDepositInterest(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey)
            throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        String bank = pe.getBank();
        String kind = "RD".equalsIgnoreCase(pe.getInstrumentKind()) ? "RD" : "FD";
        BigDecimal tds = pe.getTds() != null && pe.getTds().signum() > 0 ? pe.getTds() : null;
        if (pe.getAmount() == null) {
            if (tds == null) throw new ImportRejectedException("The deposit interest amount could not be read.");
            for (var i : incomeRepo.findByUserIdAndSourceAndIncomeDateBetweenOrderByIncomeDateDesc(userId,
                    com.marketai.income.entity.IncomeSource.INTEREST, date.minusDays(45), date.plusDays(45))) {
                // Only interest from the same bank: equal TDS at another bank is a different advice.
                boolean sameBank = bank != null && com.marketai.common.ledger.PartyNames.anySame(
                    new String[]{bank}, new String[]{i.getPayer(), i.getDescription()});
                if (!sameBank) continue;
                if (i.getTds() != null && i.getTds().compareTo(tds) == 0) return false; // already attached
                if (i.getTds() == null) {
                    i.setTds(tds);
                    i.setAmount(i.getAmount().add(tds)); // the credit was net; interest earned is gross
                    i.setNote(truncate((i.getNote() == null ? "" : i.getNote() + " ") + "TDS ₹" + tds + " added from a later advice.", 500));
                    incomeRepo.save(i);
                    return true;
                }
            }
            throw new ImportRejectedException("TDS of ₹" + tds.toPlainString() + " was withheld from " + kind + " interest"
                + (bank != null ? " at " + bank : "") + ", but the interest it came from isn't on record yet.");
        }
        BigDecimal gross = pe.getAmount().add(tds != null ? tds : BigDecimal.ZERO);
        String desc = kind + " interest" + (bank != null ? " — " + bank : "");
        if (isDuplicateIncome(userId, pe.toBuilder().amount(gross).build(), date, desc, gmailMessageId, lineKey)) {
            return false;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(gross)
            .tds(tds)
            .source(com.marketai.income.entity.IncomeSource.INTEREST)
            .incomeDate(date)
            .payer(bank)
            .sourceEmailId(gmailMessageId)
            .sourceFingerprint(lineKey)
            .note("Auto-imported from email" + (tds != null ? " — credited ₹" + pe.getAmount() + " after TDS ₹" + tds : ""))
            .build());
        return true;
    }

    /* ── Refunds and own-account transfers ───────────────────── */

    /** Not scored by {@link TransactionMatchScorer}, but just as able to repeat identically. */
    static final java.util.Set<ParsedEmail.Type> REPEATABLE_CASH_TYPES = java.util.EnumSet.of(
        ParsedEmail.Type.REFUND, ParsedEmail.Type.OWN_TRANSFER, ParsedEmail.Type.DEPOSIT_INTEREST);

    static final int REFUND_LOOKBACK_DAYS = 120;

    /**
     * A refund reduces the spend it reverses. It is booked as a negative expense in that
     * purchase's category, linked to it, so the purchase itself is never edited and both rows
     * stay traceable to their emails. With no purchase to link to it is held for review:
     * booking it as income inflated income and left the spend in place.
     */
    private boolean importRefund(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey)
            throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        BigDecimal amount = pe.getAmount();
        if (amount == null || amount.signum() <= 0) throw new ImportRejectedException("The refund amount could not be read.");
        String merchant = pe.getMerchant();
        if (merchant == null || merchant.isBlank()) {
            throw new ImportRejectedException("A refund of ₹" + amount.toPlainString() + " on " + date
                + " doesn't say who it is from, so it can't be matched to the purchase it reverses.");
        }
        BigDecimal negative = amount.negate();
        if (isDuplicateExpense(userId, pe.toBuilder().amount(negative).build(), date, merchant, "Refund — " + merchant,
                gmailMessageId, lineKey)) {
            return false;
        }
        com.marketai.expense.entity.Expense original = null;
        for (com.marketai.expense.entity.Expense e : expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(
                userId, date.minusDays(REFUND_LOOKBACK_DAYS), date)) {
            if (e.getAmount() == null || e.getAmount().signum() <= 0 || e.getRefundOfExpenseId() != null) continue;
            if (e.getCategory() == com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER) continue;
            if (!com.marketai.common.ledger.PartyNames.anySame(new String[]{merchant},
                    new String[]{e.getMerchant(), e.getDescription()})) continue;
            BigDecimal refundedSoFar = expenseRepo.findByRefundOfExpenseId(e.getId()).stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (e.getAmount().subtract(refundedSoFar).compareTo(amount) < 0) continue;
            // An exact-amount purchase beats a larger one (a full refund of that order), and the
            // most recent beats an older one — the list is newest first.
            if (original == null || (e.getAmount().compareTo(amount) == 0 && original.getAmount().compareTo(amount) != 0)) {
                original = e;
            }
        }
        if (original == null) {
            throw new ImportRejectedException("A refund of ₹" + amount.toPlainString() + " from " + merchant + " on " + date
                + " matches no purchase from " + merchant + " of at least that amount in the previous "
                + REFUND_LOOKBACK_DAYS + " days, so it was not booked.");
        }
        expenseRepo.save(com.marketai.expense.entity.Expense.builder()
            .userId(userId)
            .description("Refund — " + (original.getDescription() != null ? original.getDescription() : merchant))
            .amount(negative)
            .category(original.getCategory())
            .expenseDate(date)
            .merchant(original.getMerchant() != null ? original.getMerchant() : merchant)
            .paymentMethod(pe.getPaymentMethod() != null ? pe.getPaymentMethod() : original.getPaymentMethod())
            .planCategoryOverride(original.getPlanCategoryOverride())
            .refundOfExpenseId(original.getId())
            .sourceEmailId(gmailMessageId)
            .sourceFingerprint(lineKey)
            .note(truncate("Refund of the ₹" + original.getAmount() + " purchase on " + original.getExpenseDate()
                + " — auto-imported from email", 500))
            .build());
        return true;
    }

    static final int TRANSFER_PAIR_DAYS = 3;

    /**
     * Money moved between the user's own accounts is neither spending nor income. The outgoing
     * leg is kept as an account transfer (visible, excluded from spend); the incoming leg is the
     * same money arriving, so it is matched to that outgoing leg rather than booked again.
     */
    private boolean importOwnTransfer(Long userId, ParsedEmail pe, String gmailMessageId, String lineKey)
            throws ImportRejectedException {
        LocalDate date = requireDate(pe);
        BigDecimal amount = pe.getAmount();
        if (amount == null || amount.signum() <= 0) throw new ImportRejectedException("The transfer amount could not be read.");
        if (!Boolean.TRUE.equals(pe.getIncoming())) {
            String desc = "Transfer to own account" + (pe.getMerchant() != null ? " — " + pe.getMerchant() : "");
            if (isDuplicateExpense(userId, pe, date, pe.getMerchant(), desc, gmailMessageId, lineKey)) return false;
            expenseRepo.save(com.marketai.expense.entity.Expense.builder()
                .userId(userId)
                .description(desc)
                .amount(amount)
                .category(com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER)
                .expenseDate(date)
                .merchant(pe.getMerchant())
                .paymentMethod(pe.getPaymentMethod())
                .sourceEmailId(gmailMessageId)
                .sourceFingerprint(lineKey)
                .note("Own-account transfer — not spending. Auto-imported from email")
                .build());
            return true;
        }
        for (com.marketai.expense.entity.Expense e : expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(
                userId, date.minusDays(TRANSFER_PAIR_DAYS), date.plusDays(TRANSFER_PAIR_DAYS))) {
            if (e.getCategory() != com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER) continue;
            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) continue;
            String arrived = "Arrived " + date + (pe.getPaymentMethod() != null ? " in " + pe.getPaymentMethod() : "") + ".";
            if (e.getNote() != null && e.getNote().contains(arrived)) return false; // already paired
            e.setNote(truncate((e.getNote() == null ? "" : e.getNote() + " ") + arrived, 500));
            expenseRepo.save(e);
            log.info("Paired incoming own-account transfer of ₹{} with outgoing leg {}", amount, e.getId());
            return false;
        }
        throw new ImportRejectedException("₹" + amount.toPlainString() + " arrived on " + date
            + " from one of your own accounts, but the matching outgoing transfer isn't recorded. Nothing was booked — "
            + "it is not income. Dismiss this if the sending account isn't tracked here.");
    }

    private Portfolio getOrCreatePortfolio(Long userId, User user) throws Exception {
        List<Portfolio> portfolios = portfolioService.getUserPortfolios(userId);
        if (!portfolios.isEmpty()) return portfolios.get(0);
        return portfolioService.createPortfolio(userId, "My Portfolio", "Auto-created");
    }

    private boolean importTrade(Long userId, User user, ParsedEmail pe, com.marketai.common.ledger.Provenance provenance) throws Exception {
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

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, quantity, pe.getPrice(), pe.getTradeReference())) {
            log.debug("Skipping duplicate trade: {} {} @ {} on {}", symbol, pe.getQuantity(), pe.getPrice(), date);
            return false;
        }

        if (pe.getType() == ParsedEmail.Type.TRADE_SELL) {
            Long holdingId = portfolioService.findHoldingId(portfolio.getId(), symbol);
            if (holdingId != null) {
                sell(portfolio.getId(), holdingId, userId, quantity, pe.getPrice(), date, provenance, pe.getCharges());
                log.info("Imported SELL: {} x {} @ ₹{}", symbol, quantity, pe.getPrice());
            } else {
                log.warn("SELL trade for {} but no holding found — recording as transaction only", symbol);
                return importSellAsTransaction(portfolio.getId(), userId, symbol, pe, date, quantity);
            }
            return true;
        }

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol(symbol);
        req.setName(pe.getSymbol());
        req.setQuantity(quantity);
        req.setPrice(pe.getPrice());
        req.setTransactionDate(date);
        // Brokerage, STT, GST and stamp duty, when the contract note states them: they are part
        // of what the shares cost, and leaving them out overstated every later gain.
        req.setCharges(pe.getCharges() != null && pe.getCharges().signum() > 0 ? pe.getCharges() : BigDecimal.ZERO);
        req.setBroker(pe.getExchange());
        req.setIsin(pe.getIsin());
        req.setDpId(pe.getDpId());
        req.setClientId(pe.getClientId());
        req.setProvenance(provenance);

        portfolioService.addHolding(portfolio.getId(), userId, req);
        return true;
    }

    /** A sale the ledger refuses (more units than held) goes to review instead of being clamped. */
    private void sell(Long portfolioId, Long holdingId, Long userId, BigDecimal qty, BigDecimal price,
                      LocalDate date, com.marketai.common.ledger.Provenance provenance) throws ImportRejectedException {
        sell(portfolioId, holdingId, userId, qty, price, date, provenance, null);
    }

    private void sell(Long portfolioId, Long holdingId, Long userId, BigDecimal qty, BigDecimal price,
                      LocalDate date, com.marketai.common.ledger.Provenance provenance, BigDecimal charges)
            throws ImportRejectedException {
        try {
            portfolioService.sellHolding(portfolioId, holdingId, userId, qty, price, date, provenance,
                charges != null && charges.signum() > 0 ? charges : BigDecimal.ZERO);
        } catch (IllegalArgumentException e) {
            throw new ImportRejectedException(e.getMessage());
        }
    }

    /**
     * Records an unmatchable sale as proceeds, not as a gain.
     *
     * <p>Nothing here knows the cost basis — that is the whole reason this branch exists — so the
     * row is booked under {@code UNMATCHED_SALE}. It used to be booked under {@code CAPITAL_GAIN},
     * which {@code TaxService} taxes directly: a ₹1,50,000 sale whose true gain was ₹8,000 added
     * ₹1,50,000 to the year's taxable gains and inflated the estimate by tens of thousands.
     */
    private boolean importSellAsTransaction(Long portfolioId, Long userId, String symbol,
            ParsedEmail pe, LocalDate date, BigDecimal quantity) throws ImportRejectedException {
        BigDecimal saleValue = pe.getPrice().multiply(quantity);
        String desc = "Sale of " + symbol.replace(".NS", "").replace(".BO", "");
        if (isDuplicateIncome(userId, pe.toBuilder().amount(saleValue).build(), date, desc, null, null)) {
            log.debug("Skipping duplicate sell-as-income: {} on {} for ₹{}", desc, date, saleValue);
            return false;
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
        return true;
    }

    private boolean importMf(Long userId, User user, ParsedEmail pe, com.marketai.common.ledger.Provenance provenance) throws Exception {
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
        // The name-derived symbol is only a label; the ISIN is the fund's identity. It finds
        // the holding when a statement spells the name differently, and it keeps a fund's
        // Growth and IDCW options apart when their names share the first 30 characters.
        symbol = portfolioService.resolveFundSymbol(portfolio.getId(), symbol, pe.getIsin());
        LocalDate date = requireDate(pe);

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, units, nav)) {
            log.debug("Skipping duplicate MF import: {} {} units @ {} on {}", symbol, units, nav, date);
            return false;
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
            sell(portfolio.getId(), holdingId, userId, units, nav, date, provenance);
            log.info("Imported MF REDEMPTION: {} x {} units @ ₹{}", symbol, units, nav);
            return true;
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
        req.setProvenance(provenance);

        portfolioService.addHolding(portfolio.getId(), userId, req);
        if (pe.isIdcwReinvest() && pe.getAmount() != null && pe.getAmount().signum() > 0) {
            // A reinvested IDCW is still a dividend for tax: it is income received and then spent
            // on new units. Booking only the units left it out of the year's dividend income.
            incomeRepo.save(com.marketai.income.entity.Income.builder()
                .userId(userId)
                .description("IDCW reinvested — " + req.getName())
                .amount(pe.getAmount().add(pe.getTds() != null && pe.getTds().signum() > 0 ? pe.getTds() : BigDecimal.ZERO))
                .tds(pe.getTds() != null && pe.getTds().signum() > 0 ? pe.getTds() : null)
                .source(com.marketai.income.entity.IncomeSource.DIVIDEND)
                .incomeDate(date)
                .payer(req.getName())
                .sourceEmailId(provenance.getSourceEmailId())
                .sourceFingerprint(provenance.getSourceFingerprint())
                .note("Reinvested into " + units.stripTrailingZeros().toPlainString() + " units — no cash was received")
                .build());
        }
        return true;
    }
}
