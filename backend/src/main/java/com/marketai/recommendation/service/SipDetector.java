package com.marketai.recommendation.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a fund is being bought through a SIP or was a lump sum, from the user's own records:
 * a recorded SIP (Recurring investments) first, otherwise a monthly pattern of purchases in the
 * holding's ledger. A fund with neither is treated as a lump-sum holding.
 */
@Service
@RequiredArgsConstructor
public class SipDetector {

    public record SipInfo(String status, BigDecimal amount) {
        static final SipInfo NONE = new SipInfo("NONE", null);
    }

    private final RecurringInvestmentRepository recurringRepository;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public SipInfo detect(Long userId, String symbol) {
        if (userId == null || symbol == null) return SipInfo.NONE;
        RecurringInvestment paused = null;
        for (RecurringInvestment r : recurringRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (r.getType() != RecurringInvestment.Type.SIP || r.getLinkedSymbol() == null
                    || !r.getLinkedSymbol().equalsIgnoreCase(symbol)) continue;
            if ("ACTIVE".equals(r.getStatus())) return new SipInfo("ACTIVE", r.getAmount());
            if ("PAUSED".equals(r.getStatus()) && paused == null) paused = r;
        }
        if (paused != null) return new SipInfo("PAUSED", paused.getAmount());

        List<LocalDate> buys = new ArrayList<>();
        boolean statedSip = false;
        for (var p : portfolioRepository.findByUserId(userId)) {
            Holding h = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), symbol).orElse(null);
            if (h == null) continue;
            for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId())) {
                if (t.getType() != Transaction.TransactionType.BUY || t.getTransactionDate() == null) continue;
                // An opening balance from a holdings summary is not a purchase.
                if (t.getNotes() != null && OPENING_BALANCE.matcher(t.getNotes()).find()) continue;
                if (t.getNotes() != null && SIP_NOTE.matcher(t.getNotes()).find()) statedSip = true;
                buys.add(t.getTransactionDate());
            }
        }
        LocalDate today = LocalDate.now();
        return statedSip || looksMonthly(buys, today) || looksDailyOrWeekly(buys, today)
            ? new SipInfo("INFERRED", null) : SipInfo.NONE;
    }

    /** The fund house's own line says so: "SIP instalment 240/1303". */
    static final java.util.regex.Pattern SIP_NOTE = java.util.regex.Pattern.compile("(?i)\\bsip\\b|systematic investment");
    static final java.util.regex.Pattern OPENING_BALANCE = java.util.regex.Pattern.compile("(?i)imported from cas summary|opening balance");

    /**
     * A daily or weekly SIP: three or more purchases on distinct dates, none more than 8 days
     * apart, the latest within the last 15 days.
     */
    static boolean looksDailyOrWeekly(List<LocalDate> dates, LocalDate today) {
        List<LocalDate> d = dates.stream().distinct().sorted().toList();
        if (d.size() < 3) return false;
        if (ChronoUnit.DAYS.between(d.get(d.size() - 1), today) > 15) return false;
        for (int i = 1; i < d.size(); i++) {
            if (ChronoUnit.DAYS.between(d.get(i - 1), d.get(i)) > 8) return false;
        }
        return true;
    }

    /**
     * Three or more purchases on distinct dates roughly a month apart (25–35 days, allowing one
     * gap for a missed or late instalment), the latest within the last 45 days.
     */
    static boolean looksMonthly(List<LocalDate> dates, LocalDate today) {
        List<LocalDate> d = dates.stream().distinct().sorted().toList();
        if (d.size() < 3) return false;
        if (ChronoUnit.DAYS.between(d.get(d.size() - 1), today) > 45) return false;
        int monthly = 0, gaps = d.size() - 1;
        for (int i = 1; i < d.size(); i++) {
            long g = ChronoUnit.DAYS.between(d.get(i - 1), d.get(i));
            if (g >= 25 && g <= 35) monthly++;
        }
        return monthly >= 2 && monthly >= gaps - 1;
    }
}
