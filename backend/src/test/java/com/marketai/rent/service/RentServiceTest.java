package com.marketai.rent.service;

import com.marketai.rent.dto.RentRequest;
import com.marketai.rent.dto.RentResponse;
import com.marketai.rent.dto.RentScheduleRequest;
import com.marketai.rent.dto.RentScheduleResponse;
import com.marketai.rent.entity.Rent;
import com.marketai.rent.entity.RentSchedule;
import com.marketai.rent.repository.RentRepository;
import com.marketai.rent.repository.RentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RentServiceTest {

    private RentRepository rentRepository;
    private RentScheduleRepository scheduleRepository;
    private RentService service;

    @BeforeEach
    void setUp() {
        rentRepository = mock(RentRepository.class);
        scheduleRepository = mock(RentScheduleRepository.class);
        service = new RentService(rentRepository, scheduleRepository);
        when(rentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scheduleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void addScheduleWritesNoPlaceholderAndTheInstalmentStillShows() {
        RentScheduleRequest req = new RentScheduleRequest();
        req.setAmount(new BigDecimal("31000"));
        req.setDueDayOfMonth(28);
        req.setPaidTo("Landlord");

        RentScheduleResponse resp = service.addSchedule(1L, req);

        assertThat(resp.isActive()).isTrue();
        verify(rentRepository, never()).save(any());

        RentSchedule schedule = RentSchedule.builder().id(4L).userId(1L).amount(new BigDecimal("31000"))
                .dueDayOfMonth(28).paidTo("Landlord").active(true).build();
        when(scheduleRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(schedule));
        when(rentRepository.findByUserIdOrderByMonthDesc(1L)).thenReturn(List.of());

        List<RentResponse> rows = service.listRent(1L);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getId()).isNull();
            assertThat(r.getScheduleId()).isEqualTo(4L);
            assertThat(r.getMonth()).isEqualTo(LocalDate.now().withDayOfMonth(1));
        });
        verify(rentRepository, never()).save(any());
    }

    @Test
    void unpaidInstalmentsAreOverdueAfterTheDueDayAndMissedOnceTheMonthHasPassed() {
        RentSchedule dueOn5th = RentSchedule.builder().dueDayOfMonth(5).build();
        LocalDate sept = LocalDate.of(2026, 9, 1);
        assertThat(RentService.statusOf(null, sept, dueOn5th, LocalDate.of(2026, 9, 3))).isEqualTo("UPCOMING");
        assertThat(RentService.statusOf(null, sept, dueOn5th, LocalDate.of(2026, 9, 6))).isEqualTo("OVERDUE");
        assertThat(RentService.statusOf(null, sept, dueOn5th, LocalDate.of(2026, 10, 2))).isEqualTo("MISSED");
        assertThat(RentService.statusOf(LocalDate.of(2026, 9, 9), sept, dueOn5th, LocalDate.of(2026, 10, 2))).isEqualTo("PAID");
    }

    @Test
    void payingRentByHandThatTheEmailAlreadyRecordedAddsNothing() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Rent fromEmail = Rent.builder().id(3L).userId(1L).month(month).amount(new BigDecimal("31000"))
                .paidDate(LocalDate.of(2026, 9, 2)).paidTo("Landlord").sourceEmailId("msg-9").build();
        when(rentRepository.findByUserIdAndMonth(1L, month)).thenReturn(List.of(fromEmail));

        RentRequest req = new RentRequest();
        req.setMonth(month);
        req.setAmount(new BigDecimal("31000"));

        RentResponse resp = service.recordPayment(1L, req);

        assertThat(resp.isAlreadyRecorded()).isTrue();
        assertThat(resp.getId()).isEqualTo(3L);
        verify(rentRepository, never()).save(any());
    }

    @Test
    void theEmailForRentAlreadyMarkedPaidByHandIsLinkedNotBookedAgain() {
        LocalDate debit = LocalDate.of(2026, 9, 3);
        Rent byHand = Rent.builder().id(8L).userId(1L).month(LocalDate.of(2026, 9, 1)).amount(new BigDecimal("31000"))
                .paidDate(LocalDate.of(2026, 9, 1)).paidTo("Landlord").build();
        when(rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(any(), any(), any())).thenReturn(List.of());
        when(rentRepository.findByUserIdAndAmountAndSourceEmailIdIsNullAndPaidDateBetween(
                eq(1L), eq(new BigDecimal("31000")), any(), any())).thenReturn(List.of(byHand));

        service.matchOrCreateFromGmail(1L, new BigDecimal("31000"), debit, "LANDLORD", "msg-4");

        verify(rentRepository, times(1)).save(argThat(r -> r.getId() == 8L && "msg-4".equals(r.getSourceEmailId())));
    }

    @Test
    void recordPaymentFillsExistingOpenPlaceholderRatherThanCreatingASecondRow() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Rent placeholder = Rent.builder().id(9L).userId(1L).month(month)
                .amount(new BigDecimal("31000")).paidTo("Landlord").build();
        when(rentRepository.findByUserIdAndMonth(1L, month)).thenReturn(List.of(placeholder));

        RentRequest req = new RentRequest();
        req.setMonth(month);
        req.setAmount(new BigDecimal("31000"));
        req.setPaidDate(LocalDate.of(2026, 9, 3));

        RentResponse resp = service.recordPayment(1L, req);

        assertThat(resp.getId()).isEqualTo(9L);
        assertThat(resp.getStatus()).isEqualTo("PAID");
        verify(rentRepository, times(1)).save(any());
    }

    @Test
    void recordPaymentWithNoOpenPlaceholderCreatesOneTimeRow() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        when(rentRepository.findByUserIdAndMonth(1L, month)).thenReturn(List.of());

        RentRequest req = new RentRequest();
        req.setMonth(month);
        req.setAmount(new BigDecimal("31000"));
        req.setPaidDate(LocalDate.of(2026, 9, 3));

        RentResponse resp = service.recordPayment(1L, req);

        assertThat(resp.getId()).isNull(); // never persisted with a real id by this mock — a fresh row
        assertThat(resp.getStatus()).isEqualTo("PAID");
    }

    @Test
    void matchOrCreateFromGmailFillsOpenPlaceholderInsteadOfDuplicating() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        LocalDate paidDate = LocalDate.of(2026, 9, 1);
        Rent placeholder = Rent.builder().id(5L).userId(1L).month(month)
                .amount(new BigDecimal("31000")).paidTo("Landlord").build();
        when(rentRepository.existsByUserIdAndSourceEmailId(1L, "msg-1")).thenReturn(false);
        when(rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(1L, month, new BigDecimal("31000")))
                .thenReturn(List.of(placeholder));

        service.matchOrCreateFromGmail(1L, new BigDecimal("31000"), paidDate, "Landlord Pvt Ltd", "msg-1");

        verify(rentRepository).save(argThat(r ->
                r.getId() != null && r.getId() == 5L
                        && r.getPaidDate().equals(paidDate)
                        && "msg-1".equals(r.getSourceEmailId())));
    }

    @Test
    void matchOrCreateFromGmailIsIdempotentForTheSameEmail() {
        LocalDate paid = LocalDate.of(2026, 9, 1);
        when(rentRepository.countByUserIdAndSourceEmailIdAndAmountAndPaidDate(1L, "msg-1", new BigDecimal("31000"), paid))
                .thenReturn(1L);

        service.matchOrCreateFromGmail(1L, new BigDecimal("31000"), paid, "Landlord", "msg-1");

        verify(rentRepository, never()).save(any());
    }

    @Test
    void aSecondRentLineInTheSameEmailIsStillBooked() {
        // Two flats paid on one day from one statement: the second line (occurrence 1) is booked
        // even though the email already produced one rent row for that amount and date.
        LocalDate paid = LocalDate.of(2026, 9, 1);
        when(rentRepository.countByUserIdAndSourceEmailIdAndAmountAndPaidDate(1L, "msg-3", new BigDecimal("31000"), paid))
                .thenReturn(1L);
        when(rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(any(), any(), any())).thenReturn(List.of());

        service.matchOrCreateFromGmail(1L, new BigDecimal("31000"), paid, "Landlord B", "msg-3", 1);

        verify(rentRepository).save(any());
    }

    @Test
    void matchOrCreateFromGmailWithNoMatchCreatesNewRentRowNotAnExpense() {
        LocalDate paidDate = LocalDate.of(2026, 9, 1);
        LocalDate month = paidDate.withDayOfMonth(1);
        when(rentRepository.existsByUserIdAndSourceEmailId(1L, "msg-2")).thenReturn(false);
        when(rentRepository.findByUserIdAndMonthAndAmountAndPaidDateIsNull(1L, month, new BigDecimal("31000")))
                .thenReturn(Collections.emptyList());

        service.matchOrCreateFromGmail(1L, new BigDecimal("31000"), paidDate, "New Landlord", "msg-2");

        verify(rentRepository).save(argThat(r ->
                r.getId() == null && r.getPaidTo().equals("New Landlord") && r.getMonth().equals(month)));
    }
}
