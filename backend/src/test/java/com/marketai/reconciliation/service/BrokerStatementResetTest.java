package com.marketai.reconciliation.service;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.reconciliation.entity.DataBackup;
import com.marketai.reconciliation.repository.DataBackupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrokerStatementResetTest {

    static final String CSV = """
        CLIENT NAME,TEST
        EQUITY
        Scrip Name,Total Qty,Avg. Buy Price,Invested Value,Previous Close,Current Value
        HEROMOTOCO,0,5082.63,0.00,5199.00,0.00
        HEROMOTORS,750,127.90,95925.00,105.69,79267.50
        RELIANCE,79,1257.34,99329.86,1201.80,94942.20
        BPCL,25,309.00,7725.00,290.40,7260.00

        TOTAL,,,202979.86,,181469.70
        MUTUAL FUND
        Scrip Name,Total Qty,Avg. Buy Price,Invested Value
        Some Fund,943,2119.93,1999899.52
        """;

    @Test
    @DisplayName("the equity table is read and checked against its own TOTAL; the fund table is left out")
    void readsTheEquityTable() {
        var rows = BrokerHoldingsStatement.parseCsv(CSV);
        assertThat(rows).extracting(BrokerHoldingsStatement.Row::symbol).containsExactly("HEROMOTOCO", "HEROMOTORS", "RELIANCE", "BPCL");
        assertThatThrownBy(() -> BrokerHoldingsStatement.parseCsv(CSV.replace("750,127.90", "760,127.90")))
            .hasMessageContaining("HEROMOTORS");
        assertThatThrownBy(() -> BrokerHoldingsStatement.parseCsv(CSV.replace("202979.86", "212979.86")))
            .hasMessageContaining("TOTAL");
    }

    private static final Long USER = 2L, PF = 1L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 27);

    private final Map<Long, Holding> holdings = new LinkedHashMap<>();
    private final List<Transaction> trades = new ArrayList<>();
    private final List<EmailReviewItem> reviews = new ArrayList<>();
    private BrokerStatementResetService service;
    private Portfolio portfolio;
    private DataBackup backup;
    private long nextId = 1000;

    @BeforeEach
    void setup() {
        portfolio = Portfolio.builder().id(PF).name("Mirae Asset").build();
        PortfolioRepository pr = mock(PortfolioRepository.class);
        when(pr.findByIdAndUserId(PF, USER)).thenReturn(Optional.of(portfolio));
        HoldingRepository hr = mock(HoldingRepository.class);
        when(hr.findByPortfolioId(PF)).thenAnswer(i -> new ArrayList<>(holdings.values()));
        when(hr.save(any(Holding.class))).thenAnswer(i -> { Holding h = i.getArgument(0); if (h.getId() == null) h.setId(nextId++); holdings.put(h.getId(), h); return h; });
        doAnswer(i -> { holdings.remove(((Holding) i.getArgument(0)).getId()); return null; }).when(hr).delete(any(Holding.class));
        TransactionRepository tr = mock(TransactionRepository.class);
        when(tr.findByHoldingIdOrderByTransactionDateAscIdAsc(anyLong())).thenAnswer(i ->
            trades.stream().filter(t -> t.getHolding().getId().equals(i.getArgument(0))).toList());
        when(tr.save(any(Transaction.class))).thenAnswer(i -> { Transaction t = i.getArgument(0); if (trades.stream().noneMatch(x -> x == t)) trades.add(t); return t; });
        doAnswer(i -> { Object t = i.getArgument(0); trades.removeIf(x -> x == t); return null; }).when(tr).delete(any(Transaction.class));
        DataBackupRepository br = mock(DataBackupRepository.class);
        backup = DataBackup.builder().id(5L).userId(USER).build();
        backup.setCreatedAt(LocalDateTime.now());
        when(br.findByIdAndUserId(5L, USER)).thenReturn(Optional.of(backup));
        EmailReviewItemRepository rr = mock(EmailReviewItemRepository.class);
        when(rr.findByUserIdAndStatusOrderByCreatedAtDesc(USER, ReviewStatus.PENDING)).thenReturn(reviews);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any())).thenReturn(List.of("contract.notes@mstock.com"));
        service = new BrokerStatementResetService(pr, hr, tr, mock(PortfolioService.class), br, rr, jdbc);

        // The real mess in miniature.
        Holding heroCo = holding(15, "HEROMOTOCO", 14, "5459.97");
        trade(heroCo, "BUY", 14, "5459.97", LocalDate.of(2026, 10, 14));
        Holding heroMisread = holding(104, "HEROMOTOCO.NS", 910, "134.55");          // Hero Motors trades under the wrong name
        trade(heroMisread, "BUY", 500, "111.52", LocalDate.of(2026, 10, 23));
        Holding heroMotors = holding(16, "HEROMOTORS", 50, "155.33");
        trade(heroMotors, "BUY", 50, "155.33", LocalDate.of(2026, 10, 14));
        Holding rel = holding(33, "RELIANCE", 79, "1257.34");
        trade(rel, "BUY", 79, "1257.34", LocalDate.of(2026, 10, 14));
        Holding relNs = holding(107, "RELIANCE.NS", 20, "1169.40");                    // the same share twice
        trade(relNs, "BUY", 20, "1169.40", LocalDate.of(2026, 10, 22));
        trade(relNs, "BUY", 3, "1200.00", LocalDate.of(2026, 10, 28).minusDays(0));     // after the statement: kept
        Holding fund = holding(115, "SOMEFUND.MF", 121, "90.17");
        trade(fund, "BUY", 121, "90.17", LocalDate.of(2026, 10, 1));
        reviews.add(EmailReviewItem.builder().userId(USER).status(ReviewStatus.PENDING).proposedType("STOCK_SELL")
            .sender("m.Stock <contract.notes@mstock.com>").transactionDate(LocalDate.of(2026, 10, 9)).build());
        reviews.add(EmailReviewItem.builder().userId(USER).status(ReviewStatus.PENDING).proposedType("STOCK_SELL")
            .sender("Upstox <x@upstox.com>").transactionDate(LocalDate.of(2026, 10, 9)).build());
    }

    private Holding holding(long id, String symbol, int qty, String avg) {
        Holding h = Holding.builder().id(id).portfolio(portfolio).symbol(symbol).name(symbol)
            .quantity(BigDecimal.valueOf(qty)).averageCost(new BigDecimal(avg)).build();
        holdings.put(id, h);
        portfolio.getHoldings().add(h);
        return h;
    }

    private void trade(Holding h, String type, int qty, String price, LocalDate on) {
        trades.add(Transaction.builder().holding(h).type(Transaction.TransactionType.valueOf(type))
            .quantity(BigDecimal.valueOf(qty)).price(new BigDecimal(price)).transactionDate(on).build());
    }

    @Test
    @DisplayName("preview: the misnamed Hero holding goes, Hero Motors becomes 750, the two Reliance rows become one")
    void previewShowsEveryChange() {
        var p = service.preview(USER, PF, AS_OF, BrokerHoldingsStatement.parseCsv(CSV));
        Map<String, BrokerStatementResetService.Line> by = new HashMap<>();
        p.getLines().forEach(l -> by.put(l.getSymbol(), l));
        assertThat(by.get("HEROMOTOCO").getAction()).isEqualTo("REMOVE");
        assertThat(by.get("HEROMOTORS").getAction()).isEqualTo("ADJUST");
        assertThat(by.get("RELIANCE").getMerged()).containsExactly("RELIANCE.NS");
        assertThat(by.get("RELIANCE").getLaterTrades()).isEqualTo(1);
        assertThat(by.get("BPCL").getAction()).isEqualTo("ADD");
        assertThat(by).doesNotContainKey("SOMEFUND.MF");
        assertThat(p.getReviewsClosed()).isEqualTo(1);
        assertThat(holdings).hasSize(6);   // nothing written
    }

    @Test
    @DisplayName("apply: holdings become the statement's, later trades stay, funds untouched, older reviews closed")
    void applyResetsToTheStatement() {
        service.apply(USER, PF, AS_OF, BrokerHoldingsStatement.parseCsv(CSV), 5L, "RESET");

        assertThat(holdings.values()).extracting(Holding::getSymbol)
            .containsExactlyInAnyOrder("HEROMOTORS", "RELIANCE", "SOMEFUND.MF", "BPCL");
        assertThat(portfolio.getHoldings()).as("orphan removal re-saves a holding still in the portfolio's collection")
            .extracting(Holding::getSymbol).doesNotContain("HEROMOTOCO", "HEROMOTOCO.NS", "RELIANCE.NS");
        Map<String, List<Transaction>> bySymbol = new HashMap<>();
        for (Transaction t : trades) bySymbol.computeIfAbsent(t.getHolding().getSymbol(), k -> new ArrayList<>()).add(t);
        assertThat(bySymbol.get("HEROMOTORS")).singleElement().satisfies(t -> {
            assertThat(t.getQuantity()).isEqualByComparingTo("750");
            assertThat(t.getPrice()).isEqualByComparingTo("127.90");
            assertThat(t.getNotes()).contains("buy date unknown");
        });
        assertThat(bySymbol.get("RELIANCE")).extracting(t -> t.getQuantity().intValue()).containsExactlyInAnyOrder(79, 3);
        assertThat(bySymbol.get("SOMEFUND.MF")).hasSize(1);
        assertThat(portfolio.getSharesAsOf()).isEqualTo(AS_OF);
        assertThat(reviews.get(0).getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(reviews.get(1).getStatus()).as("another broker's").isEqualTo(ReviewStatus.PENDING);
        assertThat(backup.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("apply refuses without the confirmation word or with a used backup")
    void refusesWithoutConfirmation() {
        var rows = BrokerHoldingsStatement.parseCsv(CSV);
        assertThatThrownBy(() -> service.apply(USER, PF, AS_OF, rows, 5L, "yes")).isInstanceOf(ResponseStatusException.class);
        backup.setAppliedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.apply(USER, PF, AS_OF, rows, 5L, "RESET")).hasMessageContaining("already used");
        assertThat(holdings).hasSize(6);
    }
}
