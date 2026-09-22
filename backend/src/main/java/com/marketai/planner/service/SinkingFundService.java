package com.marketai.planner.service;

import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.entity.SinkingFund;
import com.marketai.planner.entity.SinkingFundEntry;
import com.marketai.planner.repository.SinkingFundEntryRepository;
import com.marketai.planner.repository.SinkingFundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The PDF's "Annual Travel Fund Tracker" page — a 12-month PLANNED/ADDED/USED/BALANCE ledger per
 * fund. Balance is always computed from the entries, never stored (see SinkingFundEntry). */
@Service
@RequiredArgsConstructor
public class SinkingFundService {

    private final SinkingFundRepository fundRepository;
    private final SinkingFundEntryRepository entryRepository;

    @Transactional
    public List<SinkingFundResponse> listFunds(Long userId) {
        ensureSeeded(userId);
        return fundRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(userId).stream()
            .map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    void ensureSeeded(Long userId) {
        if (!fundRepository.existsByUserId(userId)) {
            fundRepository.saveAll(PlanCategoryDefaults.seedSinkingFunds(userId));
        }
    }

    @Transactional
    public SinkingFundResponse addFund(Long userId, SinkingFundRequest req) {
        ensureSeeded(userId);
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fund name is required");
        }
        int nextOrder = fundRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(userId).size();
        SinkingFund saved = fundRepository.save(SinkingFund.builder()
            .userId(userId).name(req.getName())
            .monthlyPlanned(req.getMonthlyPlanned() != null ? req.getMonthlyPlanned() : BigDecimal.ZERO)
            .annualTarget(req.getAnnualTarget())
            .sortOrder(nextOrder)
            .build());
        return toDto(saved);
    }

    @Transactional
    public SinkingFundResponse updateFund(Long userId, Long id, SinkingFundRequest req) {
        SinkingFund fund = fundRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fund not found"));
        if (req.getName() != null) fund.setName(req.getName());
        if (req.getMonthlyPlanned() != null) fund.setMonthlyPlanned(req.getMonthlyPlanned());
        if (req.getAnnualTarget() != null) fund.setAnnualTarget(req.getAnnualTarget());
        if (req.getActive() != null) fund.setActive(req.getActive());
        return toDto(fundRepository.save(fund));
    }

    @Transactional
    public SinkingFundLedgerResponse getLedger(Long userId, Long fundId, int year) {
        SinkingFund fund = fundRepository.findByIdAndUserId(fundId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fund not found"));

        List<SinkingFundEntry> allEntries = entryRepository.findByFundIdOrderByYearMonthAsc(fundId);
        Map<String, SinkingFundEntry> byMonth = allEntries.stream()
            .collect(Collectors.toMap(SinkingFundEntry::getYearMonth, e -> e));

        // A fund carrying ₹40,000 into January has ₹40,000 in January, not ₹0. Starting the
        // running balance at zero reported a saved-up travel fund as empty every 1 January and
        // made the year's ending balance wrong by the whole carry-over.
        YearMonth firstOfYear = YearMonth.of(year, Month.JANUARY);
        BigDecimal openingBalance = BigDecimal.ZERO;
        for (SinkingFundEntry e : allEntries) {
            YearMonth ym = parseYearMonth(e.getYearMonth());
            if (ym == null || !ym.isBefore(firstOfYear)) continue;
            openingBalance = openingBalance.add(nz(e.getAdded())).subtract(nz(e.getUsed()));
        }

        List<SinkingFundLedgerRow> rows = new ArrayList<>();
        BigDecimal totalPlanned = BigDecimal.ZERO, totalAdded = BigDecimal.ZERO, totalUsed = BigDecimal.ZERO;
        BigDecimal runningBalance = openingBalance;

        for (Month m : Month.values()) {
            String ym = YearMonth.of(year, m).toString();
            SinkingFundEntry entry = byMonth.get(ym);
            BigDecimal planned = entry != null && entry.getPlanned() != null ? entry.getPlanned() : nz(fund.getMonthlyPlanned());
            BigDecimal added = entry != null ? nz(entry.getAdded()) : BigDecimal.ZERO;
            BigDecimal used = entry != null ? nz(entry.getUsed()) : BigDecimal.ZERO;
            runningBalance = runningBalance.add(added).subtract(used);

            totalPlanned = totalPlanned.add(planned);
            totalAdded = totalAdded.add(added);
            totalUsed = totalUsed.add(used);

            rows.add(SinkingFundLedgerRow.builder()
                .yearMonth(ym).planned(planned).added(added).used(used).balance(runningBalance)
                .build());
        }

        return SinkingFundLedgerResponse.builder()
            .fundId(fund.getId()).fundName(fund.getName()).year(year).rows(rows)
            .totalPlanned(totalPlanned).totalAdded(totalAdded).totalUsed(totalUsed)
            .openingBalance(openingBalance)
            .endingBalance(runningBalance)
            .build();
    }

    @Transactional
    public SinkingFundLedgerResponse upsertEntry(Long userId, Long fundId, SinkingFundEntryRequest req) {
        SinkingFund fund = fundRepository.findByIdAndUserId(fundId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fund not found"));
        if (req.getYearMonth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "yearMonth is required");
        }
        YearMonth ym = YearMonth.parse(req.getYearMonth());
        SinkingFundEntry entry = entryRepository.findByFundIdAndYearMonth(fundId, ym.toString())
            .orElseGet(() -> SinkingFundEntry.builder().fundId(fundId).yearMonth(ym.toString())
                .planned(fund.getMonthlyPlanned()).build());
        if (req.getAdded() != null) entry.setAdded(req.getAdded());
        if (req.getUsed() != null) entry.setUsed(req.getUsed());
        entryRepository.save(entry);
        return getLedger(userId, fundId, ym.getYear());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private SinkingFundResponse toDto(SinkingFund f) {
        return SinkingFundResponse.builder()
            .id(f.getId()).name(f.getName()).monthlyPlanned(f.getMonthlyPlanned())
            .annualTarget(f.getAnnualTarget()).active(f.isActive())
            .build();
    }

    /** Entries store "2026-01"; a malformed value is skipped rather than aborting the ledger. */
    private static YearMonth parseYearMonth(String value) {
        try {
            return value == null ? null : YearMonth.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
