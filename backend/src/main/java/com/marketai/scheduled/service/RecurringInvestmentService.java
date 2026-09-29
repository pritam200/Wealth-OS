package com.marketai.scheduled.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.scheduled.dto.AmountChange;
import com.marketai.scheduled.dto.InstallmentStatus;
import com.marketai.scheduled.dto.RecurringInvestmentRequest;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.dto.RecurringInvestmentUpdateRequest;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.entity.RecurringInvestmentHistory;
import com.marketai.scheduled.repository.RecurringInvestmentHistoryRepository;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecurringInvestmentService {

    private final RecurringInvestmentRepository repo;
    private final RecurringInvestmentHistoryRepository historyRepo;
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public RecurringInvestmentResponse add(Long userId, RecurringInvestmentRequest req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        RecurringInvestment ri = RecurringInvestment.builder()
            .user(user)
            .type(RecurringInvestment.Type.valueOf(req.getType()))
            .label(req.getLabel())
            .linkedSymbol(req.getLinkedSymbol())
            .sourceAccountId(req.getSourceAccountId())
            .amount(req.getAmount())
            .startDate(req.getStartDate() != null ? req.getStartDate() : LocalDate.now())
            .tenureMonths(req.getTenureMonths())
            .status(req.getStatus() != null ? req.getStatus() : "ACTIVE")
            .build();
        return toResponse(repo.save(ri));
    }

    /**
     * Partial update (spec §18: edit/pause/resume/change amount/change bank/change
     * destination). Every amount or status change is recorded to
     * {@link RecurringInvestmentHistory} before being applied — the schedule row always
     * holds only the current value, so a report can reconstruct "Jan–Jun ₹5,000, Jul–Sep
     * ₹7,500" from history without ever rewriting a past period's committed amount.
     */
    @Transactional
    public RecurringInvestmentResponse update(Long userId, Long id, RecurringInvestmentUpdateRequest req) {
        RecurringInvestment ri = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!ri.getUser().getId().equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");

        if (req.getAmount() != null && req.getAmount().compareTo(ri.getAmount()) != 0) {
            recordChange(id, "amount", ri.getAmount().toPlainString(), req.getAmount().toPlainString());
            ri.setAmount(req.getAmount());
        }
        if (req.getStatus() != null && !req.getStatus().equals(ri.getStatus())) {
            recordChange(id, "status", ri.getStatus(), req.getStatus());
            ri.setStatus(req.getStatus());
        }
        if (req.getSourceAccountId() != null) ri.setSourceAccountId(req.getSourceAccountId());
        if (req.getLinkedSymbol() != null) ri.setLinkedSymbol(req.getLinkedSymbol());
        if (req.getLabel() != null) ri.setLabel(req.getLabel());

        return toResponse(repo.save(ri));
    }

    private void recordChange(Long recurringInvestmentId, String field, String oldValue, String newValue) {
        historyRepo.save(RecurringInvestmentHistory.builder()
            .recurringInvestmentId(recurringInvestmentId)
            .field(field).oldValue(oldValue).newValue(newValue)
            .build());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        RecurringInvestment ri = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!ri.getUser().getId().equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        repo.deleteById(id);
    }

    public List<RecurringInvestmentResponse> list(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResponse).collect(Collectors.toList());
    }

    private boolean linksToHolding(RecurringInvestment.Type type) {
        return type == RecurringInvestment.Type.SIP
            || type == RecurringInvestment.Type.STOCK_SIP
            || type == RecurringInvestment.Type.ETF_SIP;
    }

    /** Statuses that end a schedule: nothing falls due after the change to them. */
    private static final java.util.Set<String> ENDED = java.util.Set.of("COMPLETED", "CANCELLED");

    /** A purchase this far short of the scheduled amount is partial. Covers stamp duty and
     *  rounding of units × NAV, which make a ₹5,000 SIP record as ₹4,999.75. */
    static final BigDecimal PARTIAL_TOLERANCE = new BigDecimal("0.02");

    /** SIP debit dates drift around "the same day each month" (holidays, processing lag). */
    static final int MATCH_WINDOW_DAYS = 10;

    /**
     * Records that one instalment's debit failed (a bounce, insufficient funds), so it shows as
     * FAILED with the reason instead of MISSED. History only — nothing in the ledger changes.
     */
    @Transactional
    public RecurringInvestmentResponse markInstallmentFailed(Long userId, Long id, LocalDate dueDate, String reason) {
        RecurringInvestment ri = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!ri.getUser().getId().equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        if (dueDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate is required");
        String why = reason == null || reason.isBlank() ? "Debit failed" : reason.trim();
        recordChange(id, "failed", why.length() > 100 ? why.substring(0, 100) : why, dueDate.toString());
        return toResponse(ri);
    }

    private RecurringInvestmentResponse toResponse(RecurringInvestment ri) {
        List<InstallmentStatus> installments;
        List<RecurringInvestmentHistory> changes = historyRepo.findByRecurringInvestmentIdOrderByChangedAtAsc(ri.getId());
        int completed = 0, missed = 0, partial = 0, failed = 0, paused = 0;

        if (linksToHolding(ri.getType()) && ri.getLinkedSymbol() != null) {
            installments = buildSipInstallments(ri, changes,
                findTransactionsForSymbol(ri.getUser().getId(), ri.getLinkedSymbol()), LocalDate.now());
            for (InstallmentStatus s : installments) {
                switch (s.getStatus()) {
                    case "COMPLETED" -> completed++;
                    case "MISSED" -> missed++;
                    case "PARTIAL" -> partial++;
                    case "FAILED" -> failed++;
                    case "PAUSED" -> paused++;
                    default -> { }
                }
            }
        } else if (ENDED.contains(ri.getStatus()) || "PAUSED".equals(ri.getStatus())) {
            installments = Collections.emptyList();
        } else {
            // PPF/NPS: no linked holding to verify against, so — same as RecurringDeposit
            // reminders today — we only surface the next scheduled due date, not a false
            // completed/missed history we have no evidence for.
            installments = Collections.singletonList(InstallmentStatus.builder()
                .dueDate(nextDueDate(ri.getStartDate(), LocalDate.now()))
                .expectedAmount(ri.getAmount())
                .status("UPCOMING").build());
        }

        List<AmountChange> history = changes.stream()
            .map(h -> AmountChange.builder().changedAt(h.getChangedAt()).field(h.getField())
                .oldValue(h.getOldValue()).newValue(h.getNewValue()).build())
            .collect(Collectors.toList());

        return RecurringInvestmentResponse.builder()
            .id(ri.getId()).type(ri.getType().name()).label(ri.getLabel()).linkedSymbol(ri.getLinkedSymbol())
            .sourceAccountId(ri.getSourceAccountId())
            .amount(ri.getAmount()).startDate(ri.getStartDate()).tenureMonths(ri.getTenureMonths()).status(ri.getStatus())
            .installments(installments).completedCount(completed).missedCount(missed)
            .partialCount(partial).failedCount(failed).pausedCount(paused)
            .amountHistory(history)
            .build();
    }

    /**
     * One row per due date, judged against what the schedule said <i>on that date</i>: the
     * amount then in force (from the change history, not today's amount), and whether it was
     * paused or already ended. Only purchases count, each at most once.
     */
    static List<InstallmentStatus> buildSipInstallments(RecurringInvestment ri, List<RecurringInvestmentHistory> changes,
                                                        List<Transaction> txns, LocalDate today) {
        LocalDate end = ri.getTenureMonths() != null ? ri.getStartDate().plusMonths(ri.getTenureMonths()).minusDays(1)
            : today.plusMonths(1);
        java.util.Map<LocalDate, String> failures = new java.util.HashMap<>();
        for (RecurringInvestmentHistory h : changes) {
            if ("failed".equals(h.getField()) && h.getNewValue() != null) {
                try { failures.put(LocalDate.parse(h.getNewValue()), h.getOldValue()); } catch (Exception ignored) { }
            }
        }
        List<Transaction> buys = txns.stream()
            .filter(t -> t.getType() == Transaction.TransactionType.BUY && t.getTransactionDate() != null)
            .collect(Collectors.toCollection(ArrayList::new));

        List<InstallmentStatus> out = new ArrayList<>();
        int guard = 0;
        for (LocalDate due = ri.getStartDate(); !due.isAfter(end) && !due.isAfter(today.plusMonths(1)) && guard++ < 120;
             due = ri.getStartDate().plusMonths(guard)) {
            final LocalDate dueDate = due;
            String statusThen = valueOn(changes, "status", dueDate, ri.getStatus());
            if (ENDED.contains(statusThen)) {
                // Ended by a recorded change on or before this date: nothing more fell due. Ended
                // with no recorded change (set that way from the start) only stops what is still
                // to come — the instalments before today were real.
                if (changedOnOrBefore(changes, "status", dueDate) || dueDate.isAfter(today)) break;
                statusThen = "ACTIVE";
            }
            BigDecimal expected = amountOn(changes, dueDate, ri.getAmount());
            InstallmentStatus.InstallmentStatusBuilder row = InstallmentStatus.builder().dueDate(dueDate).expectedAmount(expected);
            if ("PAUSED".equals(statusThen)) {
                out.add(row.status("PAUSED").build());
                continue;
            }
            Optional<Transaction> match = buys.stream()
                .filter(t -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(dueDate, t.getTransactionDate())) <= MATCH_WINDOW_DAYS)
                .min(java.util.Comparator.comparingLong(t -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(dueDate, t.getTransactionDate()))));
            if (match.isPresent()) {
                buys.remove(match.get());
                BigDecimal actual = match.get().getTotalAmount();
                boolean shortfall = expected != null && actual != null
                    && actual.compareTo(expected.multiply(BigDecimal.ONE.subtract(PARTIAL_TOLERANCE))) < 0;
                out.add(row.status(shortfall ? "PARTIAL" : "COMPLETED").actualAmount(actual).build());
            } else if (failures.containsKey(dueDate)) {
                out.add(row.status("FAILED").note(failures.get(dueDate)).build());
            } else if (dueDate.isAfter(today)) {
                out.add(row.status("UPCOMING").build());
            } else if (!dueDate.plusDays(MATCH_WINDOW_DAYS).isBefore(today)) {
                // Due, but the debit may still be processing: not yet evidence of a miss.
                out.add(row.status("UPCOMING").build());
            } else {
                out.add(row.status("MISSED").build());
            }
        }
        return out;
    }

    /** The value of a tracked field in force on {@code date}, reconstructed from its changes. */
    static String valueOn(List<RecurringInvestmentHistory> changes, String field, LocalDate date, String current) {
        String value = null;
        String firstOld = null;
        for (RecurringInvestmentHistory h : changes) {
            if (!field.equals(h.getField()) || h.getChangedAt() == null) continue;
            if (firstOld == null) firstOld = h.getOldValue();
            if (!h.getChangedAt().toLocalDate().isAfter(date)) value = h.getNewValue();
        }
        if (value != null) return value;
        return firstOld != null ? firstOld : current;
    }

    private static boolean changedOnOrBefore(List<RecurringInvestmentHistory> changes, String field, LocalDate date) {
        return changes.stream().anyMatch(h -> field.equals(h.getField()) && h.getChangedAt() != null
            && !h.getChangedAt().toLocalDate().isAfter(date));
    }

    static BigDecimal amountOn(List<RecurringInvestmentHistory> changes, LocalDate date, BigDecimal current) {
        String v = valueOn(changes, "amount", date, null);
        if (v == null) return current;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return current; }
    }

    private List<Transaction> findTransactionsForSymbol(Long userId, String symbol) {
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            Optional<Holding> h = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), symbol);
            if (h.isPresent()) return transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.get().getId());
        }
        return Collections.emptyList();
    }

    private LocalDate nextDueDate(LocalDate startDate, LocalDate today) {
        LocalDate candidate = LocalDate.of(today.getYear(), today.getMonthValue(),
            Math.min(startDate.getDayOfMonth(), today.lengthOfMonth()));
        if (candidate.isBefore(today)) candidate = candidate.plusMonths(1);
        return candidate;
    }
}
