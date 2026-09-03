package com.marketai.reminder.service;

import com.marketai.card.repository.CreditCardRepository;
import com.marketai.reminder.dto.ReminderResponse;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.LoanRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives upcoming money events from existing tracked data — no manual entry.
 * FD maturities, RD installments, credit-card bills and loan EMIs.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final FixedDepositRepository fdRepo;
    private final RecurringDepositRepository rdRepo;
    private final CreditCardRepository cardRepo;
    private final LoanRepository loanRepo;
    private final RecurringInvestmentRepository recurringInvestmentRepo;

    public List<ReminderResponse> getReminders(Long userId) {
        List<ReminderResponse> out = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // FD maturities (active FDs maturing within 45 days or already matured)
        fdRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(fd -> {
            if (fd.getMaturityDate() == null) return;
            if (!"ACTIVE".equalsIgnoreCase(fd.getStatus() == null ? "ACTIVE" : fd.getStatus())) return;
            long d = ChronoUnit.DAYS.between(today, fd.getMaturityDate());
            if (d <= 45) {
                out.add(ReminderResponse.builder()
                    .type("FD_MATURITY")
                    .title("FD maturing — " + fd.getBank())
                    .subtitle("Principal ₹" + strip(fd.getPrincipal()) + " @ " + strip(fd.getRate()) + "%")
                    .dueDate(fd.getMaturityDate()).daysUntil(d)
                    .amount(fd.getPrincipal()).severity(sev(d)).build());
            }
        });

        // RD installments (next monthly installment within 10 days)
        rdRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(rd -> {
            if (rd.getStartDate() == null) return;
            LocalDate next = nextMonthly(rd.getStartDate(), rd.getStartDate().getDayOfMonth(), today);
            LocalDate end = rd.getStartDate().plusMonths(rd.getTenureMonths());
            if (next.isAfter(end)) return;
            long d = ChronoUnit.DAYS.between(today, next);
            if (d <= 10) {
                out.add(ReminderResponse.builder()
                    .type("RD_INSTALLMENT")
                    .title("RD installment — " + rd.getBank())
                    .subtitle("Monthly ₹" + strip(rd.getMonthlyAmount()))
                    .dueDate(next).daysUntil(d)
                    .amount(rd.getMonthlyAmount()).severity(sev(d)).build());
            }
        });

        // Credit-card bills (from parsed statement emails)
        cardRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(c -> {
            if (c.getCurrentDue() == null || c.getCurrentDue().signum() <= 0 || c.getCurrentDueDate() == null) return;
            long d = ChronoUnit.DAYS.between(today, c.getCurrentDueDate());
            if (d <= 20) {
                out.add(ReminderResponse.builder()
                    .type("CARD_BILL")
                    .title("Card bill — " + c.getName())
                    .subtitle("Total due ₹" + strip(c.getCurrentDue()))
                    .dueDate(c.getCurrentDueDate()).daysUntil(d)
                    .amount(c.getCurrentDue()).severity(sev(d)).build());
            }
        });

        // Loan EMIs (assume due on the 5th each month)
        loanRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(l -> {
            if (l.getEmi() == null || l.getEmi().signum() <= 0 || l.getRemainingMonths() <= 0) return;
            LocalDate next = nextMonthly(today.withDayOfMonth(1), 5, today);
            long d = ChronoUnit.DAYS.between(today, next);
            if (d <= 7) {
                out.add(ReminderResponse.builder()
                    .type("LOAN_EMI")
                    .title("EMI — " + l.getName())
                    .subtitle("Monthly ₹" + strip(l.getEmi()))
                    .dueDate(next).daysUntil(d)
                    .amount(l.getEmi()).severity(sev(d)).build());
            }
        });

        // SIP / PPF / NPS recurring contributions (next due date within 10 days)
        recurringInvestmentRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(ri -> {
            if (!"ACTIVE".equalsIgnoreCase(ri.getStatus())) return;
            if (ri.getTenureMonths() != null && ri.getStartDate().plusMonths(ri.getTenureMonths()).isBefore(today)) return;
            LocalDate next = nextMonthly(ri.getStartDate(), ri.getStartDate().getDayOfMonth(), today);
            long d = ChronoUnit.DAYS.between(today, next);
            if (d <= 10) {
                String type = ri.getType() == RecurringInvestment.Type.SIP ? "SIP_DUE"
                    : ri.getType() == RecurringInvestment.Type.PPF ? "PPF_CONTRIBUTION" : "NPS_CONTRIBUTION";
                out.add(ReminderResponse.builder()
                    .type(type)
                    .title(ri.getType().name() + " due — " + ri.getLabel())
                    .subtitle("₹" + strip(ri.getAmount()) + "/month")
                    .dueDate(next).daysUntil(d)
                    .amount(ri.getAmount()).severity(sev(d)).build());
            }
        });

        out.sort(Comparator.comparingLong(ReminderResponse::getDaysUntil));
        return out;
    }

    private LocalDate nextMonthly(LocalDate anchor, int dayOfMonth, LocalDate today) {
        LocalDate candidate = safeDate(today.getYear(), today.getMonthValue(), dayOfMonth);
        if (candidate.isBefore(today)) candidate = candidate.plusMonths(1);
        return candidate;
    }

    private LocalDate safeDate(int y, int m, int day) {
        LocalDate first = LocalDate.of(y, m, 1);
        return first.withDayOfMonth(Math.min(day, first.lengthOfMonth()));
    }

    private String sev(long d) { return d < 0 ? "OVERDUE" : d <= 7 ? "DUE_SOON" : "UPCOMING"; }

    private String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
