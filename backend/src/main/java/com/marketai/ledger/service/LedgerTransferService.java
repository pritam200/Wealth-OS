package com.marketai.ledger.service;

import com.marketai.auth.entity.User;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.investmentplan.service.PlannedInvestmentMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Records movements between the user's own assets and applies the cash-side effect.
 *
 * The invariant this service exists to protect: **a transfer must never change net worth.**
 * It is enforced structurally rather than by a check — the only balance mutations here are
 * equal-and-opposite (debit source, credit destination), and for a transfer into a non-cash
 * asset only the cash side is touched, because the asset side is created by that asset's own
 * import path. Nothing here ever adds an Income or Expense row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerTransferService {

    private final LedgerTransferRepository transferRepo;
    private final CashAccountRepository accountRepo;
    private final PlannedInvestmentMatcher plannedInvestmentMatcher;

    @Transactional
    public LedgerTransfer record(User user, Long sourceAccountId, Long destinationAccountId,
                                 String destinationType, String destinationRef,
                                 BigDecimal amount, LocalDate date, String note, String sourceEmailId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A transfer amount greater than zero is required");
        }
        if (destinationType == null || destinationType.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destinationType is required");
        }

        CashAccount source = sourceAccountId == null ? null
            : accountRepo.findByIdAndUser_Id(sourceAccountId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source account not found"));
        CashAccount destination = destinationAccountId == null ? null
            : accountRepo.findByIdAndUser_Id(destinationAccountId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination account not found"));

        if (source != null && destination != null && source.getId().equals(destination.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source and destination cannot be the same account");
        }

        LedgerTransfer transfer = transferRepo.save(LedgerTransfer.builder()
            .user(user)
            .sourceAccount(source)
            .destinationAccount(destination)
            .destinationType(destinationType.trim().toUpperCase())
            .destinationRef(destinationRef)
            .amount(amount)
            .transferDate(date != null ? date : LocalDate.now())
            .note(note)
            .sourceEmailId(sourceEmailId)
            .applied(false)
            .build());

        applyCashEffect(transfer);
        LedgerTransfer saved = transferRepo.save(transfer);
        // Spec §7: automatic reconciliation — a plan line is never marked complete by the
        // user, only by real activity like this transfer being matched onto it.
        plannedInvestmentMatcher.matchTransfer(user.getId(), saved);
        return saved;
    }

    /**
     * Debits the source and credits the destination by the same amount. Guarded by the
     * `applied` flag so replaying or re-saving a transfer can never move the money twice.
     */
    private void applyCashEffect(LedgerTransfer transfer) {
        if (Boolean.TRUE.equals(transfer.getApplied())) return;

        CashAccount source = transfer.getSourceAccount();
        CashAccount destination = transfer.getDestinationAccount();

        if (source != null) {
            source.setBalance(nz(source.getBalance()).subtract(transfer.getAmount()));
            accountRepo.save(source);
        }
        if (destination != null) {
            destination.setBalance(nz(destination.getBalance()).add(transfer.getAmount()));
            accountRepo.save(destination);
        }
        transfer.setApplied(true);
    }

    /**
     * Reverses a transfer's cash effect and deletes it. Used when a transfer was recorded in
     * error — the balances must return exactly to where they were, which is why the reversal
     * is the same equal-and-opposite operation rather than a recomputation.
     */
    @Transactional
    public void delete(User user, Long id) {
        LedgerTransfer transfer = transferRepo.findByIdAndUser_Id(id, user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));

        if (Boolean.TRUE.equals(transfer.getApplied())) {
            CashAccount source = transfer.getSourceAccount();
            CashAccount destination = transfer.getDestinationAccount();
            if (source != null) {
                source.setBalance(nz(source.getBalance()).add(transfer.getAmount()));
                accountRepo.save(source);
            }
            if (destination != null) {
                destination.setBalance(nz(destination.getBalance()).subtract(transfer.getAmount()));
                accountRepo.save(destination);
            }
        }
        transferRepo.delete(transfer);
    }

    public List<LedgerTransfer> list(Long userId) {
        return transferRepo.findByUser_IdOrderByTransferDateDesc(userId);
    }

    /** Total tracked cash — the component that was missing from net worth entirely. */
    public BigDecimal totalCash(Long userId) {
        return nz(accountRepo.sumBalanceByUser(userId));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
