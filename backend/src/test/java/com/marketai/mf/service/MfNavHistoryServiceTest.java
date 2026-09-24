package com.marketai.mf.service;

import com.marketai.mf.entity.MfNavHistory;
import com.marketai.mf.repository.MfNavHistoryRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the write-back that was missing: MF holdings previously froze at import-time cost
 * basis forever because nothing ever applied the nightly-refreshed {@code mf_nav_history} back
 * onto {@code Holding.currentPrice}.
 */
class MfNavHistoryServiceTest {

    private WebClient.Builder webClientBuilder;
    private MfNavHistoryRepository navHistoryRepository;
    private HoldingRepository holdingRepository;
    private MfNavHistoryService service;

    @BeforeEach
    void setUp() {
        webClientBuilder = mock(WebClient.Builder.class);
        navHistoryRepository = mock(MfNavHistoryRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        service = new MfNavHistoryService(webClientBuilder, navHistoryRepository, holdingRepository);
    }

    @Test
    void latestNavReturnsEmptyWhenNoHistoryStored() {
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("123456")).thenReturn(Optional.empty());

        assertThat(service.latestNav("123456")).isEmpty();
    }

    @Test
    void latestNavReturnsMostRecentlyStoredNav() {
        MfNavHistory row = MfNavHistory.builder().schemeCode("123456")
                .date(LocalDate.of(2026, 9, 20)).nav(new BigDecimal("110.5000")).build();
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("123456")).thenReturn(Optional.of(row));

        assertThat(service.latestNav("123456")).contains(new BigDecimal("110.5000"));
    }

    @Test
    void syncHoldingValuationsWritesLatestNavOntoEveryLinkedHolding() {
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("123456"))
                .thenReturn(Optional.of(MfNavHistory.builder().schemeCode("123456")
                        .date(LocalDate.of(2026, 9, 20)).nav(new BigDecimal("157.70")).build()));

        Holding staleHolding = Holding.builder().id(1L).symbol("HDFCMID.MF").amfiSchemeCode("123456")
                .quantity(new BigDecimal("10")).averageCost(new BigDecimal("142.99")).currentPrice(new BigDecimal("142.99")).build();
        when(holdingRepository.findByAmfiSchemeCode("123456")).thenReturn(List.of(staleHolding));

        int updated = service.syncHoldingValuations("123456");

        assertThat(updated).isEqualTo(1);
        assertThat(staleHolding.getCurrentPrice()).isEqualByComparingTo("157.70");
        verify(holdingRepository).save(staleHolding);
    }

    @Test
    void syncHoldingValuationsSkipsHoldingsAlreadyAtTheLatestNav() {
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("123456"))
                .thenReturn(Optional.of(MfNavHistory.builder().schemeCode("123456")
                        .date(LocalDate.of(2026, 9, 20)).nav(new BigDecimal("157.70")).build()));

        Holding upToDate = Holding.builder().id(2L).symbol("HDFCMID.MF").amfiSchemeCode("123456")
                .quantity(new BigDecimal("10")).averageCost(new BigDecimal("142.99")).currentPrice(new BigDecimal("157.70")).build();
        when(holdingRepository.findByAmfiSchemeCode("123456")).thenReturn(List.of(upToDate));

        int updated = service.syncHoldingValuations("123456");

        assertThat(updated).isEqualTo(0);
        verify(holdingRepository, never()).save(any());
    }

    @Test
    void syncHoldingValuationsSetsCurrentPriceEvenWhenPreviouslyNull() {
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("123456"))
                .thenReturn(Optional.of(MfNavHistory.builder().schemeCode("123456")
                        .date(LocalDate.of(2026, 9, 20)).nav(new BigDecimal("157.70")).build()));

        Holding neverPriced = Holding.builder().id(3L).symbol("HDFCMID.MF").amfiSchemeCode("123456")
                .quantity(new BigDecimal("10")).averageCost(new BigDecimal("142.99")).currentPrice(null).build();
        when(holdingRepository.findByAmfiSchemeCode("123456")).thenReturn(List.of(neverPriced));

        int updated = service.syncHoldingValuations("123456");

        assertThat(updated).isEqualTo(1);
        assertThat(neverPriced.getCurrentPrice()).isEqualByComparingTo("157.70");
    }

    @Test
    void syncHoldingValuationsIsNoOpWhenNoHistoryExistsForScheme() {
        when(navHistoryRepository.findTopBySchemeCodeOrderByDateDesc("999999")).thenReturn(Optional.empty());

        int updated = service.syncHoldingValuations("999999");

        assertThat(updated).isEqualTo(0);
        verify(holdingRepository, never()).findByAmfiSchemeCode(any());
    }
}
