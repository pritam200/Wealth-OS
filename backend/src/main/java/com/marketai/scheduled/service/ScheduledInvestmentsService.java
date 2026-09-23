package com.marketai.scheduled.service;

import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.dto.ScheduledInvestmentSummary;
import com.marketai.tracking.dto.RdResponse;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only merge of RecurringInvestment (SIP/PPF/NPS/STOCK_SIP/ETF_SIP/BROKER_RECURRING)
 * and RecurringDeposit (RD) into one "Scheduled Investments" view (spec §4/§16) — RD keeps
 * living in its own table/service (TrackingService), it is only presented alongside the
 * others here, never merged into RecurringInvestment's schema.
 */
@Service
@RequiredArgsConstructor
public class ScheduledInvestmentsService {

    private final RecurringInvestmentService recurringInvestmentService;
    private final TrackingService trackingService;

    public List<ScheduledInvestmentSummary> listAll(Long userId) {
        List<ScheduledInvestmentSummary> out = new ArrayList<>();

        for (RecurringInvestmentResponse ri : recurringInvestmentService.list(userId)) {
            out.add(ScheduledInvestmentSummary.builder()
                .sourceKind("RECURRING_INVESTMENT")
                .sourceId(ri.getId())
                .investmentType(ri.getType())
                .label(ri.getLabel())
                .amount(ri.getAmount())
                .frequency("Monthly")
                .dueDayOfMonth(ri.getStartDate() != null ? ri.getStartDate().getDayOfMonth() : null)
                .sourceAccountId(ri.getSourceAccountId())
                .destination(ri.getLinkedSymbol())
                .status(ri.getStatus())
                .startDate(ri.getStartDate())
                .build());
        }

        for (RdResponse rd : trackingService.listRds(userId)) {
            out.add(ScheduledInvestmentSummary.builder()
                .sourceKind("RECURRING_DEPOSIT")
                .sourceId(rd.getId())
                .investmentType("RD")
                .label(rd.getBank() + " RD")
                .amount(rd.getMonthlyAmount())
                .frequency("Monthly")
                .dueDayOfMonth(rd.getStartDate() != null ? rd.getStartDate().getDayOfMonth() : null)
                .destination(rd.getBank())
                .status(rd.getStatus())
                .startDate(rd.getStartDate())
                .build());
        }

        out.sort(Comparator.comparing(ScheduledInvestmentSummary::getLabel));
        return out;
    }
}
