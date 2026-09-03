package com.marketai.scheduled.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.scheduled.dto.InstallmentStatus;
import com.marketai.scheduled.dto.RecurringInvestmentRequest;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
            .amount(req.getAmount())
            .startDate(req.getStartDate() != null ? req.getStartDate() : LocalDate.now())
            .tenureMonths(req.getTenureMonths())
            .status(req.getStatus() != null ? req.getStatus() : "ACTIVE")
            .build();
        return toResponse(repo.save(ri));
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

    private RecurringInvestmentResponse toResponse(RecurringInvestment ri) {
        List<InstallmentStatus> installments;
        int completed = 0, missed = 0;

        if (ri.getType() == RecurringInvestment.Type.SIP && ri.getLinkedSymbol() != null) {
            installments = buildSipInstallments(ri);
            for (InstallmentStatus s : installments) {
                if ("COMPLETED".equals(s.getStatus())) completed++;
                else if ("MISSED".equals(s.getStatus())) missed++;
            }
        } else {
            // PPF/NPS: no linked holding to verify against, so — same as RecurringDeposit
            // reminders today — we only surface the next scheduled due date, not a false
            // completed/missed history we have no evidence for.
            installments = Collections.singletonList(InstallmentStatus.builder()
                .dueDate(nextDueDate(ri.getStartDate(), LocalDate.now()))
                .status("UPCOMING").build());
        }

        return RecurringInvestmentResponse.builder()
            .id(ri.getId()).type(ri.getType().name()).label(ri.getLabel()).linkedSymbol(ri.getLinkedSymbol())
            .amount(ri.getAmount()).startDate(ri.getStartDate()).tenureMonths(ri.getTenureMonths()).status(ri.getStatus())
            .installments(installments).completedCount(completed).missedCount(missed)
            .build();
    }

    private List<InstallmentStatus> buildSipInstallments(RecurringInvestment ri) {
        List<Transaction> txns = findTransactionsForSymbol(ri.getUser().getId(), ri.getLinkedSymbol());
        LocalDate today = LocalDate.now();
        LocalDate end = ri.getTenureMonths() != null ? ri.getStartDate().plusMonths(ri.getTenureMonths()) : today.plusMonths(1);

        List<InstallmentStatus> out = new ArrayList<>();
        LocalDate due = ri.getStartDate();
        int guard = 0;
        while (!due.isAfter(end) && !due.isAfter(today.plusMonths(1)) && guard++ < 120) {
            LocalDate dueDate = due;
            if (dueDate.isAfter(today)) {
                out.add(InstallmentStatus.builder().dueDate(dueDate).status("UPCOMING").build());
            } else {
                // A transaction within +/-10 days of the expected date counts as that
                // installment — SIP debit dates drift a little around the "same day each month".
                Optional<Transaction> match = txns.stream()
                    .filter(t -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(dueDate, t.getTransactionDate())) <= 10)
                    .findFirst();
                if (match.isPresent()) {
                    out.add(InstallmentStatus.builder().dueDate(dueDate).status("COMPLETED").actualAmount(match.get().getTotalAmount()).build());
                } else {
                    out.add(InstallmentStatus.builder().dueDate(dueDate).status("MISSED").build());
                }
            }
            due = due.plusMonths(1);
        }
        return out;
    }

    private List<Transaction> findTransactionsForSymbol(Long userId, String symbol) {
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            Optional<Holding> h = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), symbol);
            if (h.isPresent()) return transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.get().getId());
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
