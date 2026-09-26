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
import java.util.Optional;

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
    void addScheduleMaterializesCurrentMonthPlaceholder() {
        RentScheduleRequest req = new RentScheduleRequest();
        req.setAmount(new BigDecimal("31000"));
        req.setDueDayOfMonth(1);
        req.setPaidTo("Landlord");

        when(rentRepository.findByUserIdAndMonthAndScheduleId(any(), any(), any())).thenReturn(Optional.empty());

        RentScheduleResponse resp = service.addSchedule(1L, req);

        assertThat(resp.isActive()).isTrue();
        assertThat(resp.getAmount()).isEqualByComparingTo("31000");
        verify(rentRepository).save(argThat(r ->
                r.getMonth().equals(LocalDate.now().withDayOfMonth(1))
                        && r.getPaidDate() == null
                        && r.getAmount().compareTo(new BigDecimal("31000")) == 0));
    }

    @Test
    void recordPaymentFillsExistingOpenPlaceholderRatherThanCreatingASecondRow() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Rent placeholder = Rent.builder().id(9L).userId(1L).month(month)
                .amount(new BigDecimal("31000")).paidTo("Landlord").build();
        when(rentRepository.findByUserIdAndMonthAndPaidDateIsNull(1L, month)).thenReturn(Optional.of(placeholder));

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
        when(rentRepository.findByUserIdAndMonthAndPaidDateIsNull(1L, month)).thenReturn(Optional.empty());

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
