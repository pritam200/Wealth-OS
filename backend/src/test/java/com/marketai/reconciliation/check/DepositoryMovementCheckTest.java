package com.marketai.reconciliation.check;

import com.marketai.gmail.entity.DepositoryMovement;
import com.marketai.gmail.repository.DepositoryMovementRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DepositoryMovementCheckTest {

    private static final Long USER = 2L;
    private static final LocalDate D = LocalDate.of(2026, 10, 6);

    private final List<DepositoryMovement> movements = new ArrayList<>();
    private final List<Transaction> trades = new ArrayList<>();
    private DepositoryMovementCheck check;
    private Holding lt;

    @BeforeEach
    void setup() {
        DepositoryMovementRepository mr = mock(DepositoryMovementRepository.class);
        PortfolioRepository pr = mock(PortfolioRepository.class);
        HoldingRepository hr = mock(HoldingRepository.class);
        TransactionRepository tr = mock(TransactionRepository.class);
        when(mr.findByUserIdOrderByMovedAtAsc(USER)).thenReturn(movements);
        when(pr.findByUserId(USER)).thenReturn(List.of(Portfolio.builder().id(1L).name("mStock").build()));
        lt = Holding.builder().id(7L).symbol("LT").name("Larsen & Toubro Ltd").quantity(BigDecimal.TEN).build();   // no ISIN: matched by name
        when(hr.findByPortfolioId(1L)).thenReturn(List.of(lt));
        when(tr.findByHoldingIdInOrderByTransactionDateAscIdAsc(anyList())).thenReturn(trades);
        check = new DepositoryMovementCheck(mr, pr, hr, tr);
    }

    private void moved(long id, String isin, String name, String qty, boolean credit, LocalDate on, String msg) {
        movements.add(DepositoryMovement.builder().id(id).userId(USER).gmailMessageId(msg).rowNo(1).accountSuffix("5678")
            .isin(isin).companyName(name).quantity(new BigDecimal(qty)).credit(credit).movedAt(on.atTime(11, 0)).build());
    }

    private void traded(Transaction.TransactionType type, String qty, LocalDate on) {
        trades.add(Transaction.builder().holding(lt).type(type).quantity(new BigDecimal(qty)).transactionDate(on).build());
    }

    @Test
    @DisplayName("a day's credits explained by the purchases before it pass; a resent row counts once")
    void explainedMovementsPass() {
        moved(1, "INE018A01030", "LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH", "4", true, D, "a");
        moved(2, "INE018A01030", "LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH", "4", true, D, "b");
        traded(Transaction.TransactionType.BUY, "1", D.minusDays(1));
        traded(Transaction.TransactionType.BUY, "3", D.minusDays(1));
        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a debit with no sale on record, and a credit only partly booked, are reported")
    void unexplainedMovementsReported() {
        moved(1, "INE018A01030", "LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH", "5", false, D, "a");
        moved(2, "INE018A01030", "LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH", "10", true, D.minusDays(3), "b");
        traded(Transaction.TransactionType.BUY, "4", D.minusDays(4));
        traded(Transaction.TransactionType.SELL, "5", D.minusDays(9));   // too long before to be this debit
        List<ReconciliationIssue> issues = check.run(USER);
        assertThat(issues).extracting(ReconciliationIssue::getType)
            .containsExactlyInAnyOrder("DEPOSITORY_MOVEMENT_UNBOOKED", "DEPOSITORY_MOVEMENT_PART_BOOKED");
        assertThat(issues).extracting(ReconciliationIssue::getReferenceId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("a security no holding is, is reported with the depository's own name")
    void unknownSecurity() {
        moved(1, "INE009A01021", "INFOSYS LIMITED-EQUITY SHARES OF RS.5/- EACH", "10", true, D, "a");
        List<ReconciliationIssue> issues = check.run(USER);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getDescription()).startsWith("INFOSYS LIMITED").contains("No holding in your portfolio");
    }

    @Test
    @DisplayName("a movement on or before the broker statement a portfolio was reset to is already in its quantities")
    void movementsCoveredByAStatementReset() {
        Portfolio reset = Portfolio.builder().id(1L).sharesAsOf(LocalDate.of(2026, 10, 27)).build();
        Portfolio other = Portfolio.builder().id(2L).build();
        Holding inReset = Holding.builder().id(10L).portfolio(reset).symbol("LT").build();
        Holding elsewhere = Holding.builder().id(11L).portfolio(other).symbol("LT").build();
        var before = new DepositoryMovementCheck.Day("INE018A01030", "LT", true, LocalDate.of(2026, 10, 21), BigDecimal.ONE, 1L);
        var after = new DepositoryMovementCheck.Day("INE018A01030", "LT", true, LocalDate.of(2026, 10, 28), BigDecimal.ONE, 2L);
        var asOf = java.util.Map.of(1L, reset.getSharesAsOf());

        assertThat(DepositoryMovementCheck.reflectedInStatement(before, List.of(inReset), asOf, reset.getSharesAsOf())).isTrue();
        assertThat(DepositoryMovementCheck.reflectedInStatement(after, List.of(inReset), asOf, reset.getSharesAsOf())).isFalse();
        assertThat(DepositoryMovementCheck.reflectedInStatement(before, List.of(inReset, elsewhere), asOf, reset.getSharesAsOf())).isFalse();
        assertThat(DepositoryMovementCheck.reflectedInStatement(before, List.of(), asOf, reset.getSharesAsOf())).isTrue();
        assertThat(DepositoryMovementCheck.reflectedInStatement(before, List.of(), java.util.Map.of(), null)).isFalse();
    }
}
