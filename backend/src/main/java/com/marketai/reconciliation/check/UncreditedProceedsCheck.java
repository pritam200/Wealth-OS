package com.marketai.reconciliation.check;

import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.entity.RecurringDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Money that left an asset but never arrived anywhere.
 *
 * <p>Closing an FD or selling a holding reduces the asset side of net worth. The proceeds land in
 * a bank account in real life, but nothing in this app credits one: {@code sellHolding} and
 * {@code closeFd}/{@code closeRd} book the gain or interest as Income — which is not an asset —
 * and {@code LedgerTransferService} is the only thing that ever moves a {@code CashAccount}
 * balance. So a ₹5,00,000 FD maturing at ₹5,40,000 currently drops ₹5,40,000 of assets, adds ₹0
 * of cash, and books ₹40,000 of income: <b>net worth falls by ₹5,00,000 on a maturity that made
 * the user money</b>, and the net-worth attribution reports the drop as market movement.
 *
 * <p>This check reports the gap rather than repairing it, for the same reason the rest of this
 * package does: the app cannot know which account received the money, and inventing a destination
 * would be fabricating a financial record. The user records the transfer — the one thing they can
 * state and this system cannot infer — and the gap closes.
 *
 * <p>Deliberately an aggregate over a trailing window, not a per-disposal match. Pairing a
 * specific sale to a specific credit needs amount-and-date fuzzy matching, and a false "this
 * one is missing" on a disposal the user *did* record is worse than one honest total.
 */
@Component
@RequiredArgsConstructor
public class UncreditedProceedsCheck implements ReconciliationCheck {

    /** Long enough to span a settlement cycle and a user getting round to recording it. */
    private static final int WINDOW_DAYS = 90;

    /** Below this, the gap is charges and rounding across many disposals, not a missing leg. */
    private static final BigDecimal MATERIALITY = new BigDecimal("1000");

    private final TransactionRepository transactionRepository;
    private final FixedDepositRepository fdRepository;
    private final RecurringDepositRepository rdRepository;
    private final LedgerTransferRepository transferRepository;

    @Override public String id() { return "LEDGER_UNCREDITED_PROCEEDS"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "Sale or maturity proceeds that were never credited to a cash account, which "
             + "understates net worth by the full amount withdrawn";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(WINDOW_DAYS);

        BigDecimal proceeds = BigDecimal.ZERO;
        int disposals = 0;

        for (Transaction t : transactionRepository.findRecentSales(userId, since)) {
            if (t.getPrice() == null || t.getQuantity() == null) continue;
            proceeds = proceeds.add(t.getPrice().multiply(t.getQuantity()));
            disposals++;
        }
        for (FixedDeposit fd : fdRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"CLOSED".equalsIgnoreCase(fd.getStatus())) continue;
            if (fd.getClosedDate() == null || fd.getClosedDate().isBefore(since)) continue;
            BigDecimal amount = fd.getMaturityAmount() != null ? fd.getMaturityAmount() : fd.getPrincipal();
            if (amount == null) continue;
            proceeds = proceeds.add(amount);
            disposals++;
        }
        for (RecurringDeposit rd : rdRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"CLOSED".equalsIgnoreCase(rd.getStatus())) continue;
            if (rd.getClosedDate() == null || rd.getClosedDate().isBefore(since)) continue;
            if (rd.getMaturityAmount() == null) continue;
            proceeds = proceeds.add(rd.getMaturityAmount());
            disposals++;
        }

        if (disposals == 0 || proceeds.signum() <= 0) return List.of();

        BigDecimal credited = BigDecimal.ZERO;
        for (LedgerTransfer tr : transferRepository
                .findByUser_IdAndTransferDateBetweenOrderByTransferDateDesc(userId, since, today)) {
            // Only a leg that actually increased a tracked cash balance counts as the proceeds
            // arriving; a transfer out to an investment is the opposite direction.
            if (tr.getDestinationAccount() != null && tr.getAmount() != null) {
                credited = credited.add(tr.getAmount());
            }
        }

        BigDecimal gap = proceeds.subtract(credited);
        if (gap.compareTo(MATERIALITY) <= 0) return List.of();

        List<ReconciliationIssue> issues = new ArrayList<>();
        issues.add(ReconciliationIssue.builder()
            .domain(domain()).type(id()).severity("HIGH")
            .description(String.format(
                "%d disposal(s) in the last %d days released ₹%s, but only ₹%s was credited to a "
                    + "cash account — a gap of ₹%s. Until the receiving account is recorded, that "
                    + "money is missing from your net worth even though you still have it. Record "
                    + "the transfer into the account that received it.",
                disposals, WINDOW_DAYS, strip(proceeds), strip(credited), strip(gap)))
            .build());
        return issues;
    }

    private static String strip(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
