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
     * The rent ledger plus, for each active schedule, this month's instalment when nothing has
     * been recorded for it yet. That instalment is shown, not written: a read used to insert a
     * placeholder row, and two concurrent reads inserted two. A payment — entered by hand or
     * read from email — attaches to its schedule when it is recorded.
     */
    @Transactional(readOnly = true)
    public List<RentResponse> listRent(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate thisMonth = firstOfMonth(today);
        java.util.Map<Long, RentSchedule> schedules = new java.util.HashMap<>();
        for (RentSchedule s : scheduleRepository.findByUserIdOrderByCreatedAtDesc(userId)) schedules.put(s.getId(), s);

        List<Rent> rows = rentRepository.findByUserIdOrderByMonthDesc(userId);
        List<RentResponse> out = new java.util.ArrayList<>();
        for (RentSchedule s : schedules.values()) {
            if (!s.isActive()) continue;
            boolean recorded = rows.stream().anyMatch(r -> s.getId().equals(r.getScheduleId()) && thisMonth.equals(r.getMonth()));
            if (recorded) continue;
            out.add(RentResponse.builder()
                    .scheduleId(s.getId()).month(thisMonth).amount(s.getAmount()).paidTo(s.getPaidTo())
                    .cashAccountId(s.getCashAccountId()).paymentMethod(s.getPaymentMethod())
                    .status(statusOf(null, thisMonth, s, today))
                    .build());
        }
        for (Rent r : rows) {
            RentResponse resp = toResponse(r);
            resp.setStatus(statusOf(r.getPaidDate(), r.getMonth(),
                    r.getScheduleId() != null ? schedules.get(r.getScheduleId()) : null, today));
            out.add(resp);
        }
        out.sort(java.util.Comparator.comparing(RentResponse::getMonth).reversed());
        return out;
    }

    /**
     * PAID once a payment is recorded. Otherwise UPCOMING until the due day, OVERDUE after it
     * within the month, and MISSED once the month has passed.
     */
    static String statusOf(LocalDate paidDate, LocalDate month, RentSchedule schedule, LocalDate today) {
        if (paidDate != null) return "PAID";
        LocalDate thisMonth = firstOfMonth(today);
        if (month.isBefore(thisMonth)) return "MISSED";
        if (month.isAfter(thisMonth)) return "UPCOMING";
        int dueDay = schedule != null && schedule.getDueDayOfMonth() != null ? schedule.getDueDayOfMonth() : 1;
        LocalDate due = month.withDayOfMonth(Math.min(Math.max(dueDay, 1), month.lengthOfMonth()));
        return today.isAfter(due) ? "OVERDUE" : "UPCOMING";
    }

    /**
     * Manual "I paid rent" entry. Attaches to that month's open instalment (a legacy placeholder
     * row, or the schedule it belongs to) rather than creating a second row. If the same payment
     * was already read from email it is returned, flagged, instead of being recorded twice.
     */
    @Transactional
    public RentResponse recordPayment(Long userId, RentRequest req) {
        LocalDate paidDate = req.getPaidDate() != null ? req.getPaidDate() : LocalDate.now();
        LocalDate month = req.getMonth() != null ? firstOfMonth(req.getMonth()) : firstOfMonth(paidDate);
        List<Rent> monthRows = rentRepository.findByUserIdAndMonth(userId, month);

        if (!req.isConfirmSeparate()) {
            Rent fromEmail = monthRows.stream()
                    .filter(r -> r.getPaidDate() != null && r.getSourceEmailId() != null)
                    .filter(r -> r.getAmount() != null && r.getAmount().compareTo(req.getAmount()) == 0)
                    .filter(r -> req.getScheduleId() == null || r.getScheduleId() == null || req.getScheduleId().equals(r.getScheduleId()))
                    .filter(r -> payeeCompatible(req.getPaidTo(), r.getPaidTo()))
                    .findFirst().orElse(null);
            if (fromEmail != null) {
                RentResponse existing = toResponse(fromEmail);
                existing.setAlreadyRecorded(true);
                return existing;
            }
        }

        Rent rent = monthRows.stream()
                .filter(r -> r.getPaidDate() == null)
                .filter(r -> req.getScheduleId() == null || req.getScheduleId().equals(r.getScheduleId()))
                .filter(r -> payeeCompatible(req.getPaidTo(), r.getPaidTo()))
                .findFirst()
                .orElseGet(() -> Rent.builder().userId(userId).month(month)
                        .scheduleId(freeSchedule(userId, month, req.getScheduleId(), req.getAmount(), req.getPaidTo(), monthRows))
                        .build());
        rent.setAmount(req.getAmount());
        rent.setPaidDate(paidDate);
        if (req.getPaidTo() != null) rent.setPaidTo(req.getPaidTo());
        if (req.getCashAccountId() != null) rent.setCashAccountId(req.getCashAccountId());
        if (req.getPaymentMethod() != null) rent.setPaymentMethod(req.getPaymentMethod());
        rent.setReferenceId(req.getReferenceId());
        rent.setNote(req.getNote());
        return toResponse(rentRepository.save(rent));
    }

    /**
     * The schedule a new payment row belongs to: the one named, or the only active schedule of
     * this amount/payee — provided that schedule has no row for the month yet (one row per
     * schedule per month). Null means a one-off payment.
     */
    private Long freeSchedule(Long userId, LocalDate month, Long named, BigDecimal amount, String payee, List<Rent> monthRows) {
        List<RentSchedule> candidates = scheduleRepository.findByUserIdAndActiveTrue(userId).stream()
                .filter(s -> named != null ? named.equals(s.getId())
                        : s.getAmount() != null && amount != null && s.getAmount().compareTo(amount) == 0
                          && payeeCompatible(payee, s.getPaidTo()))
                .filter(s -> monthRows.stream().noneMatch(r -> s.getId().equals(r.getScheduleId())))
                .toList();
        return candidates.size() == 1 ? candidates.get(0).getId() : null;
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
        matchOrCreateFromGmail(userId, amount, date, payee, sourceEmailId, 0);
    }

    /**
     * @param occurrenceInSource 0 for the first line of this content in the email, 1 for an
     *        identical second line, … A statement listing two rent payments (two flats, or two
     *        months paid on one day) books both; a re-sync books neither again. The check used to
     *        be "any rent row from this email", which dropped every payment after the first.
     */
    @Transactional
    public void matchOrCreateFromGmail(Long userId, BigDecimal amount, LocalDate date,
                                        String payee, String sourceEmailId, int occurrenceInSource) {
        if (sourceEmailId != null && rentRepository.countByUserIdAndSourceEmailIdAndAmountAndPaidDate(
                userId, sourceEmailId, amount, date) > occurrenceInSource) {
            return; // this line is already booked from this exact email — idempotent re-sync
        }
        LocalDate month = firstOfMonth(date);

        List<Rent> openSameMonth = rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(userId, month, amount);
        Rent target = openSameMonth.stream()
                .filter(r -> payeeCompatible(payee, r.getPaidTo()))
                .findFirst()
                .orElse(null);

        if (target == null && sourceEmailId != null) {
            // Already marked paid by hand: the email is the evidence for that payment, not a
            // second one. Matched on amount and a payment date within a few days, because the
            // day the user ticks "paid" is rarely the day the bank debited it.
            Rent enteredByHand = rentRepository.findByUserIdAndAmountAndSourceEmailIdIsNullAndPaidDateBetween(
                            userId, amount, date.minusDays(HAND_ENTRY_DAYS), date.plusDays(HAND_ENTRY_DAYS)).stream()
                    .filter(r -> payeeCompatible(payee, r.getPaidTo()))
                    .findFirst().orElse(null);
            if (enteredByHand != null) {
                enteredByHand.setSourceEmailId(sourceEmailId);
                if (payee != null && enteredByHand.getPaidTo() == null) enteredByHand.setPaidTo(payee);
                rentRepository.save(enteredByHand);
                return;
            }
        }

        if (target == null) {
            List<Rent> monthRows = rentRepository.findByUserIdAndMonth(userId, month);
            target = Rent.builder().userId(userId).month(month).amount(amount).paidTo(payee)
                    .scheduleId(freeSchedule(userId, month, null, amount, payee, monthRows))
                    .build();
        }

        target.setPaidDate(date);
        target.setSourceEmailId(sourceEmailId);
        if (payee != null && target.getPaidTo() == null) target.setPaidTo(payee);
        rentRepository.save(target);
    }

    /** Rent rows booked from this email for this amount and payment date. */
    public long countFromEmail(Long userId, String sourceEmailId, BigDecimal amount, LocalDate paidDate) {
        return rentRepository.countByUserIdAndSourceEmailIdAndAmountAndPaidDate(userId, sourceEmailId, amount, paidDate);
    }

    /** How far apart a hand-entered payment date and the bank's debit date may be. */
    private static final int HAND_ENTRY_DAYS = 5;

    /** Either side unnamed, or the same payee once normalised. */
    private static boolean payeeCompatible(String a, String b) {
        return a == null || a.isBlank() || b == null || b.isBlank()
                || com.marketai.common.ledger.PartyNames.sameParty(a, b);
    }

    private static LocalDate firstOfMonth(LocalDate date) {
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
