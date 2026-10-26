package com.marketai.research.service;

import com.marketai.ai.llm.LlmService;
import com.marketai.auth.entity.User;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.research.model.ResearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The daily research run: what it researches, and when it stops spending the quota. */
class ResearchSchedulerTest {

    ResearchOrchestrator research;
    LlmService llm;
    PortfolioRepository portfolios;
    HoldingRepository holdings;
    TransactionTemplate tx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        research = mock(ResearchOrchestrator.class);
        llm = mock(LlmService.class);
        portfolios = mock(PortfolioRepository.class);
        holdings = mock(HoldingRepository.class);
        tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        User u = new User();
        u.setId(2L);
        Portfolio p = new Portfolio();
        p.setId(1L);
        p.setUser(u);
        when(portfolios.findAll()).thenReturn(List.of(p));
        when(holdings.findOpenByPortfolioId(1L)).thenReturn(List.of(
                Holding.builder().symbol("HAL.NS").name("HAL").quantity(BigDecimal.ONE).build(),
                Holding.builder().symbol("HAL").name("HAL").quantity(BigDecimal.ONE).build(),
                Holding.builder().symbol("SBIAGG.MF").name("SBI fund").quantity(BigDecimal.ONE).build(),
                Holding.builder().symbol("TCS.NS").name("TCS").quantity(BigDecimal.ONE).build()));
        when(llm.quotaSpentUntil(any())).thenReturn(Optional.empty());
    }

    private ResearchScheduler scheduler(int cap) {
        return new ResearchScheduler(research, llm, portfolios, holdings, tx, true, cap, 0);
    }

    private static ResearchResult result(String status, boolean fromCache) {
        return ResearchResult.builder().status(status).fromCache(fromCache).build();
    }

    @Test
    @DisplayName("researches the market and each held stock once — funds and a second listing of the same stock are skipped")
    void marketThenHeldStocks() {
        when(research.index(eq("^NSEI"), any(), any())).thenReturn(result("OK", false));
        when(research.stock(anyString(), any(), eq(2L), any())).thenReturn(result("OK", false));

        assertThat(scheduler(10).run()).isEqualTo(3);

        verify(research).stock(eq("HAL.NS"), any(), eq(2L), eq(ResearchOrchestrator.Mode.AUTO));
        verify(research).stock(eq("TCS.NS"), any(), eq(2L), any());
        verify(research, never()).stock(eq("SBIAGG.MF"), any(), any(), any());
        verify(research, never()).stock(eq("HAL"), any(), any(), any());
    }

    @Test
    @DisplayName("subjects already researched today don't count toward the cap")
    void cachedSubjectsAreFree() {
        when(research.index(any(), any(), any())).thenReturn(result("OK", true));
        when(research.stock(anyString(), any(), any(), any())).thenReturn(result("OK", false));

        assertThat(scheduler(1).run()).isEqualTo(1);
        verify(research, times(1)).stock(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("stops once the quota is spent, or a run comes back unavailable")
    void stopsSpendingWhenRefused() {
        when(research.index(any(), any(), any())).thenReturn(result("UNAVAILABLE", false));
        assertThat(scheduler(10).run()).isEqualTo(1);
        verify(research, never()).stock(anyString(), any(), any(), any());

        reset(research);
        when(llm.quotaSpentUntil(any())).thenReturn(Optional.of(Instant.now().plusSeconds(3600)));
        assertThat(scheduler(10).run()).isZero();
        verifyNoInteractions(research);
    }
}
