package com.marketai.reconciliation.check;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class HoldingDataCompletenessCheckTest {

    private final PortfolioRepository portfolios = mock(PortfolioRepository.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final HoldingDataCompletenessCheck check = new HoldingDataCompletenessCheck(portfolios, holdings, txns);

    private Holding holding(long id, String symbol, String qty, String price, LocalDate priceAsOf, LocalDate buyDate, String scheme) {
        Holding h = new Holding();
        h.setId(id); h.setSymbol(symbol); h.setName(symbol);
        h.setQuantity(new BigDecimal(qty)); h.setAverageCost(new BigDecimal("10"));
        h.setCurrentPrice(price == null ? null : new BigDecimal(price)); h.setPriceAsOf(priceAsOf);
        h.setBuyDate(buyDate); h.setAmfiSchemeCode(scheme);
        return h;
    }

    private Transaction buy(Holding h, String qty) {
        Transaction t = new Transaction();
        t.setHolding(h); t.setType(Transaction.TransactionType.BUY);
        t.setQuantity(new BigDecimal(qty)); t.setPrice(BigDecimal.TEN); t.setTransactionDate(LocalDate.now().minusYears(1));
        return t;
    }

    private List<String> typesFor(List<Holding> hs, List<Transaction> ts) {
        Portfolio p = new Portfolio(); p.setId(1L);
        when(portfolios.findByUserId(1L)).thenReturn(List.of(p));
        when(holdings.findOpenByPortfolioId(1L)).thenReturn(hs);
        when(txns.findByHoldingIdInOrderByTransactionDateAscIdAsc(anyList())).thenReturn(ts);
        return check.run(1L).stream().map(ReconciliationIssue::getType).toList();
    }

    @Test
    void completeHoldingPasses() {
        Holding h = holding(1, "TCS.NS", "10", "4000", LocalDate.now(), LocalDate.now().minusYears(1), null);
        assertThat(typesFor(List.of(h), List.of(buy(h, "10")))).isEmpty();
    }

    @Test
    void flagsEachMissingInput() {
        Holding partial = holding(1, "ABC.MF", "100", "50", LocalDate.now(), null, null);
        Holding unpriced = holding(2, "XYZ.NS", "5", null, null, LocalDate.now().minusYears(1), null);
        assertThat(typesFor(List.of(partial, unpriced), List.of(buy(partial, "60"))))
            .containsExactlyInAnyOrder("LEDGER_INCOMPLETE", "NO_PURCHASE_DATE", "MF_NOT_LINKED",
                                       "NO_TRANSACTION_HISTORY", "NO_CURRENT_PRICE");
    }
}
