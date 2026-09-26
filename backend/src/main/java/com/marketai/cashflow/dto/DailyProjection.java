package com.marketai.cashflow.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** One day of a {@code CashFlowForecastService.forecast} result — the running cash balance
 *  after applying every event dated that day, plus the events themselves for drill-down. */
@Data
@Builder
public class DailyProjection {

    private LocalDate date;
    private BigDecimal projectedBalance;
    private List<ProjectedEvent> events;

    /** True when any event on this day is {@code ProjectedEvent.estimated} — lets a caller
     *  render the balance line itself as a dashed/uncertain segment past that point. */
    private boolean hasEstimatedComponent;
}
