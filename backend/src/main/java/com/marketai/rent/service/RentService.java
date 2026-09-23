package com.marketai.rent.service;

import com.marketai.rent.dto.RentRequest;
import com.marketai.rent.dto.RentResponse;
import com.marketai.rent.dto.RentScheduleRequest;
import com.marketai.rent.dto.RentScheduleResponse;
import com.marketai.rent.entity.Rent;
import com.marketai.rent.entity.RentSchedule;
import com.marketai.rent.repository.RentRepository;
import com.marketai.rent.repository.RentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RentService {

    private final RentRepository rentRepository;
    private final RentScheduleRepository scheduleRepository;

    @Transactional
    public RentScheduleResponse addSchedule(Long userId, RentScheduleRequest req) {
        RentSchedule schedule = scheduleRepository.save(RentSchedule.builder()
                .userId(userId)
                .amount(req.getAmount())
                .dueDayOfMonth(req.getDueDayOfMonth())
                .paidTo(req.getPaidTo())
                .cashAccountId(req.getCashAccountId())
                .paymentMethod(req.getPaymentMethod())
                .active(true)
                .build());
        // Materialize this month's placeholder immediately so "Upcoming" shows without
        // waiting for the next listRent() call to notice the new schedule.
        ensureMonthRow(userId, schedule, firstOfMonth(LocalDate.now()));
        return toScheduleResponse(schedule);
    }

    @Transactional
    public RentScheduleResponse setScheduleActive(Long userId, Long scheduleId, boolean active) {
        RentSchedule schedule = scheduleRepository.findByIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rent schedule not found"));
        schedule.setActive(active);
        return toScheduleResponse(scheduleRepository.save(schedule));
    }

    @Transactional
    public void deleteSchedule(Long userId, Long scheduleId) {
        RentSchedule schedule = scheduleRepository.findByIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rent schedule not found"));
        scheduleRepository.delete(schedule);
    }

    public List<RentScheduleResponse> listSchedules(Long userId) {
        return scheduleRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toScheduleResponse).collect(Collectors.toList());
    }

    /**
     * Ensures the current month has an "Upcoming" placeholder for every active schedule
     * before returning the ledger — mirrors RecurringInvestmentService's nextDueDate()
     * idiom, but materialized as a real row (rather than computed on the fly), since a
     * real payment — manual or Gmail-detected — needs a row to match onto.
     */
    @Transactional
    public List<RentResponse> listRent(Long userId) {
        LocalDate thisMonth = firstOfMonth(LocalDate.now());
        for (RentSchedule schedule : scheduleRepository.findByUserIdAndActiveTrue(userId)) {
            ensureMonthRow(userId, schedule, thisMonth);
        }
        return rentRepository.findByUserIdOrderByMonthDesc(userId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private void ensureMonthRow(Long userId, RentSchedule schedule, LocalDate month) {
        if (rentRepository.findByUserIdAndMonthAndScheduleId(userId, month, schedule.getId()).isPresent()) return;
        rentRepository.save(Rent.builder()
                .userId(userId)
                .scheduleId(schedule.getId())
                .month(month)
                .amount(schedule.getAmount())
                .paidTo(schedule.getPaidTo())
                .cashAccountId(schedule.getCashAccountId())
                .paymentMethod(schedule.getPaymentMethod())
                .build());
    }

    /**
     * Manual "I paid rent" entry. Fills in the matching month's open placeholder row (from
     * an active schedule) rather than creating a second row, if one exists; otherwise
     * records a genuine one-time payment.
     */
    @Transactional
    public RentResponse recordPayment(Long userId, RentRequest req) {
        LocalDate paidDate = req.getPaidDate() != null ? req.getPaidDate() : LocalDate.now();
        LocalDate month = req.getMonth() != null ? firstOfMonth(req.getMonth()) : firstOfMonth(paidDate);

        Rent rent = rentRepository.findByUserIdAndMonthAndPaidDateIsNull(userId, month)
                .orElseGet(() -> Rent.builder().userId(userId).month(month).build());
        rent.setAmount(req.getAmount());
        rent.setPaidDate(paidDate);
        if (req.getPaidTo() != null) rent.setPaidTo(req.getPaidTo());
        if (req.getCashAccountId() != null) rent.setCashAccountId(req.getCashAccountId());
        if (req.getPaymentMethod() != null) rent.setPaymentMethod(req.getPaymentMethod());
        rent.setReferenceId(req.getReferenceId());
        rent.setNote(req.getNote());
        return toResponse(rentRepository.save(rent));
    }

    @Transactional
    public void deleteRent(Long userId, Long id) {
        if (!rentRepository.existsByIdAndUserId(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rent record not found");
        }
        rentRepository.deleteById(id);
    }

    /**
     * Gmail-sync entry point (spec §2/§19). The caller (ParsedEmailImporter) has already
     * decided this parsed email looks like rent (merchant/description/category mentions
     * "rent") before calling this — this method only decides whether it matches an existing
     * placeholder/manual Rent row for that month by amount+payee, or should become a new
     * one-time Rent row, rather than ever letting it fall through to a duplicate Expense.
     */
    @Transactional
    public void matchOrCreateFromGmail(Long userId, BigDecimal amount, LocalDate date,
                                        String payee, String sourceEmailId) {
        if (sourceEmailId != null && rentRepository.existsByUserIdAndSourceEmailId(userId, sourceEmailId)) {
            return; // already booked from this exact email — idempotent re-sync
        }
        LocalDate month = firstOfMonth(date);

        List<Rent> openSameMonth = rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(userId, month, amount);
        Rent target = openSameMonth.stream()
                .filter(r -> payee == null || r.getPaidTo() == null || payeesMatch(payee, r.getPaidTo()))
                .findFirst()
                .orElseGet(() -> Rent.builder().userId(userId).month(month).amount(amount).paidTo(payee).build());

        target.setPaidDate(date);
        target.setSourceEmailId(sourceEmailId);
        if (payee != null && target.getPaidTo() == null) target.setPaidTo(payee);
        rentRepository.save(target);
    }

    private boolean payeesMatch(String a, String b) {
        return a.equalsIgnoreCase(b)
                || a.toLowerCase().contains(b.toLowerCase())
                || b.toLowerCase().contains(a.toLowerCase());
    }

    private LocalDate firstOfMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    private RentScheduleResponse toScheduleResponse(RentSchedule s) {
        return RentScheduleResponse.builder()
                .id(s.getId()).amount(s.getAmount()).dueDayOfMonth(s.getDueDayOfMonth())
                .paidTo(s.getPaidTo()).cashAccountId(s.getCashAccountId()).paymentMethod(s.getPaymentMethod())
                .active(s.isActive())
                .build();
    }

    private RentResponse toResponse(Rent r) {
        return RentResponse.builder()
                .id(r.getId()).scheduleId(r.getScheduleId()).month(r.getMonth()).amount(r.getAmount())
                .paidDate(r.getPaidDate()).paidTo(r.getPaidTo()).cashAccountId(r.getCashAccountId())
                .paymentMethod(r.getPaymentMethod()).referenceId(r.getReferenceId()).note(r.getNote())
                .sourceEmailId(r.getSourceEmailId())
                .status(r.getPaidDate() != null ? "PAID" : "UPCOMING")
                .build();
    }
}
