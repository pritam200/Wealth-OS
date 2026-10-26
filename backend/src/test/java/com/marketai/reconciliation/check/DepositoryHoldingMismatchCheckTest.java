package com.marketai.reconciliation.check;

import com.marketai.cas.CasImportService;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.FinancialAccountRepository;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.dataplatform.repo.HoldingSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Holdings built from trades, checked against the depository's statement by ISIN. */
class DepositoryHoldingMismatchCheckTest {

    private static final Long USER = 2L;
    private static final LocalDate STATEMENT = LocalDate.of(2026, 9, 30);

    private final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    private final HoldingSnapshotRepository snapshots = mock(HoldingSnapshotRepository.class);
    private final FinancialAssetRepository assets = mock(FinancialAssetRepository.class);
    private final PortfolioRepository portfolios = mock(PortfolioRepository.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final DepositoryHoldingMismatchCheck check =
        new DepositoryHoldingMismatchCheck(accounts, snapshots, assets, portfolios, holdings, txns);

    private final List<HoldingSnapshot> reported = new ArrayList<>();
    private final List<FinancialAsset> assetList = new ArrayList<>();
    private final Map<Long, List<Holding>> byPortfolio = new HashMap<>();
    private final List<Transaction> trades = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(accounts.findByOwnerUserId(USER)).thenReturn(List.of(
            demat(100L, "1208160000000001"), demat(101L, "IN30000000000002")));
        when(snapshots.findByAccountIdIn(anyCollection())).thenAnswer(i -> reported);
        when(assets.findAllById(anyIterable())).thenAnswer(i -> assetList);
        Portfolio upstox = new Portfolio(); upstox.setId(2L); upstox.setName("Upstox");
        Portfolio kotak = new Portfolio(); kotak.setId(3L); kotak.setName("Kotak Securities");
        when(portfolios.findByUserId(USER)).thenReturn(List.of(upstox, kotak));
        when(holdings.findByPortfolioId(anyLong())).thenAnswer(i -> byPortfolio.getOrDefault(i.<Long>getArgument(0), List.of()));
        when(txns.findByHoldingIdInOrderByTransactionDateAscIdAsc(anyList())).thenAnswer(i -> trades);
    }

    private static FinancialAccount demat(Long id, String bo) {
        return FinancialAccount.builder().id(id).ownerUserId(USER).accountType(AccountType.DEMAT)
            .institution(CasImportService.DEMAT_INSTITUTION).accountKey("DEMAT (NSDL/CDSL):" + bo).build();
    }

    private void line(long accountId, long assetId, String isin, String name, String qty, LocalDate asOf) {
        if (assetList.stream().noneMatch(a -> a.getId() == assetId))
            assetList.add(FinancialAsset.builder().id(assetId).assetClass(AssetClass.STOCK).isin(isin).name(name).assetKey(isin).build());
        reported.add(HoldingSnapshot.builder().accountId(accountId).assetId(assetId).basis(BasisType.INSTITUTION_REPORTED)
            .quantity(new BigDecimal(qty)).asOfDate(asOf).build());
    }

    private Holding holding(long portfolioId, long id, String symbol, String name, String isin) {
        Holding h = Holding.builder().id(id).symbol(symbol).name(name).isin(isin).quantity(BigDecimal.ZERO).averageCost(BigDecimal.ZERO).build();
        byPortfolio.computeIfAbsent(portfolioId, k -> new ArrayList<>()).add(h);
        return h;
    }

    private void trade(Holding h, Transaction.TransactionType type, String qty, LocalDate date) {
        Transaction t = new Transaction();
        t.setId((long) trades.size() + 1); t.setHolding(h); t.setType(type);
        t.setQuantity(new BigDecimal(qty)); t.setPrice(new BigDecimal("100")); t.setTransactionDate(date);
        trades.add(t);
        h.setQuantity(h.getQuantity().add(type == Transaction.TransactionType.BUY ? t.getQuantity() : t.getQuantity().negate()));
    }

    @Test
    @DisplayName("the same share bought through two brokers matches the depository's total across both demat accounts")
    void sumsAcrossBrokersAndDematAccounts() {
        line(100, 1, "INE066F01020", "HINDUSTAN AERONAUTICS LIMITED", "6", STATEMENT);
        line(101, 1, "INE066F01020", "HINDUSTAN AERONAUTICS LIMITED", "4", STATEMENT);
        trade(holding(2, 10, "HAL.NS", "Hindustan Aeronautics Ltd", "INE066F01020"), Transaction.TransactionType.BUY, "6", STATEMENT.minusMonths(2));
        trade(holding(3, 11, "HAL.NS", "Hindustan Aeronautics Ltd", null), Transaction.TransactionType.BUY, "4", STATEMENT.minusMonths(1));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a difference is reported with both figures, against the trades as of the statement date")
    void reportsADifference() {
        line(100, 1, "INE158A01026", "HERO MOTOCORP LIMITED", "5", STATEMENT);
        Holding hero = holding(2, 10, "HEROMOTOCO.NS", "Hero MotoCorp Ltd", "INE158A01026");
        trade(hero, Transaction.TransactionType.BUY, "5", STATEMENT.minusMonths(3));
        trade(hero, Transaction.TransactionType.BUY, "50", STATEMENT.minusMonths(1));   // an F&O lot read as shares
        trade(hero, Transaction.TransactionType.BUY, "7", STATEMENT.plusDays(10));      // after the statement: not counted

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.getType()).isEqualTo("DEPOSITORY_HOLDING_MISMATCH");
            assertThat(i.getReferenceId()).isEqualTo(10L);
            assertThat(i.getDescription()).contains("INE158A01026").contains("shows 5 shares").contains("give 55").contains("F&O");
        });
    }

    @Test
    @DisplayName("a trade still settling on the statement date is not a difference")
    void allowsForSettlement() {
        line(100, 1, "INE397D01024", "BHARTI AIRTEL LIMITED", "10", STATEMENT);
        Holding airtel = holding(2, 10, "BHARTIARTL.NS", "Bharti Airtel Ltd", "INE397D01024");
        trade(airtel, Transaction.TransactionType.BUY, "10", STATEMENT.minusMonths(2));
        trade(airtel, Transaction.TransactionType.BUY, "3", STATEMENT);   // bought on the statement day, settles the next

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a share on the statement with no holding is reported; one dropped from the latest statement is held at zero")
    void missingFromEitherSide() {
        line(100, 1, "INE009A01021", "INFOSYS LIMITED", "12", STATEMENT);
        line(100, 2, "INE040A01034", "HDFC BANK LIMITED", "8", STATEMENT.minusMonths(1));   // sold before the latest statement
        trade(holding(2, 10, "HDFCBANK.NS", "HDFC Bank Ltd", "INE040A01034"), Transaction.TransactionType.BUY, "8", STATEMENT.minusMonths(4));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).extracting(ReconciliationIssue::getType)
            .containsExactlyInAnyOrder("DEPOSITORY_HOLDING_NOT_IN_PORTFOLIO", "DEPOSITORY_HOLDING_MISMATCH");
        assertThat(issues).filteredOn(i -> i.getType().equals("DEPOSITORY_HOLDING_MISMATCH"))
            .singleElement().satisfies(i -> assertThat(i.getDescription()).contains("shows 0 shares").contains("give 8"));
    }

    @Test
    @DisplayName("a holding with no ISIN is matched by company name; one nothing matches is reported as unmatched")
    void matchesByNameAndReportsTheUnmatched() {
        line(100, 1, "INE066F01020", "HINDUSTAN AERONAUTICS LIMITED", "6", STATEMENT);
        trade(holding(2, 10, "HAL.NS", "Hindustan Aeronautics Ltd.", null), Transaction.TransactionType.BUY, "6", STATEMENT.minusMonths(2));
        trade(holding(2, 11, "HEROMOTOCO25NOVFUT", "HEROMOTOCO 25 NOV FUT", null), Transaction.TransactionType.BUY, "300", STATEMENT.minusDays(20));

        assertThat(check.run(USER)).singleElement().satisfies(i -> {
            assertThat(i.getType()).isEqualTo("DEPOSITORY_HOLDING_UNMATCHED");
            assertThat(i.getReferenceId()).isEqualTo(11L);
        });
    }

    @Test
    @DisplayName("no demat statement imported: nothing to compare")
    void noStatement() {
        when(accounts.findByOwnerUserId(USER)).thenReturn(List.of());
        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("company names compare without their legal suffix or share-class words")
    void companyKey() {
        assertThat(StatementPositions.companyKey("HERO MOTOCORP LIMITED")).isEqualTo(StatementPositions.companyKey("Hero MotoCorp Ltd."));
        assertThat(StatementPositions.companyKey("INFOSYS LIMITED#NEW EQUITY SHARES OF RS 5/- AFTER SPLIT"))
            .isEqualTo(StatementPositions.companyKey("Infosys Ltd"));
        assertThat(StatementPositions.companyKey("Tata Motors Ltd")).isNotEqualTo(StatementPositions.companyKey("Tata Steel Ltd"));
    }
}
