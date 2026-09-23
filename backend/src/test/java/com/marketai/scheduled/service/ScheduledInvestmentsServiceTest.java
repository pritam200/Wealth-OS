package com.marketai.scheduled.service;

import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.dto.ScheduledInvestmentSummary;
import com.marketai.tracking.dto.RdResponse;
import com.marketai.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledInvestmentsServiceTest {

    private RecurringInvestmentService riService;
    private TrackingService trackingService;
    private ScheduledInvestmentsService service;

    @BeforeEach
    void setUp() {
        riService = mock(RecurringInvestmentService.class);
        trackingService = mock(TrackingService.class);
        service = new ScheduledInvestmentsService(riService, trackingService);
    }

    @Test
    void mergesRecurringInvestmentsAndRecurringDepositsIntoOneList() {
        when(riService.list(1L)).thenReturn(List.of(
            RecurringInvestmentResponse.builder().id(1L).type("SIP").label("SBI MF SIP")
                .amount(new BigDecimal("5000")).startDate(LocalDate.of(2026, 1, 10)).status("ACTIVE")
                .linkedSymbol("SBIMF.MF").build()
        ));
        when(trackingService.listRds(1L)).thenReturn(List.of(
            RdResponse.builder().id(2L).bank("HDFC Bank").monthlyAmount(new BigDecimal("10000"))
                .startDate(LocalDate.of(2026, 1, 5)).status("ACTIVE").build()
        ));

        List<ScheduledInvestmentSummary> all = service.listAll(1L);

        assertThat(all).hasSize(2);
        assertThat(all).extracting(ScheduledInvestmentSummary::getSourceKind)
            .containsExactlyInAnyOrder("RECURRING_INVESTMENT", "RECURRING_DEPOSIT");
        ScheduledInvestmentSummary rd = all.stream().filter(s -> s.getSourceKind().equals("RECURRING_DEPOSIT")).findFirst().orElseThrow();
        assertThat(rd.getLabel()).isEqualTo("HDFC Bank RD");
        assertThat(rd.getAmount()).isEqualByComparingTo("10000");
        assertThat(rd.getDueDayOfMonth()).isEqualTo(5);
    }

    @Test
    void emptyBothSourcesYieldsEmptyList() {
        when(riService.list(any())).thenReturn(List.of());
        when(trackingService.listRds(any())).thenReturn(List.of());

        assertThat(service.listAll(1L)).isEmpty();
    }
}
