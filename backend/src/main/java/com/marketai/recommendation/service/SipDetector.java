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
        for (var p : portfolioRepository.findByUserId(userId)) {
            Holding h = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), symbol).orElse(null);
            if (h == null) continue;
            for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId())) {
                if (t.getType() == Transaction.TransactionType.BUY && t.getTransactionDate() != null) buys.add(t.getTransactionDate());
            }
        }
        return looksMonthly(buys, LocalDate.now()) ? new SipInfo("INFERRED", null) : SipInfo.NONE;
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
