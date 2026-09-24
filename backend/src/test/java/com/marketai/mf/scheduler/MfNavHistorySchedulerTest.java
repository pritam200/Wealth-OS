package com.marketai.mf.scheduler;

import com.marketai.mf.service.MfNavHistoryService;
import com.marketai.mf.service.MfSchemeLinkService;
import com.marketai.portfolio.repository.HoldingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfNavHistorySchedulerTest {

    private HoldingRepository holdingRepository;
    private MfNavHistoryService navHistoryService;
    private MfSchemeLinkService schemeLinkService;
    private MfNavHistoryScheduler scheduler;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        navHistoryService = mock(MfNavHistoryService.class);
        schemeLinkService = mock(MfSchemeLinkService.class);
        scheduler = new MfNavHistoryScheduler(holdingRepository, navHistoryService, schemeLinkService);
    }

    @Test
    void syncsHoldingValuationsForEveryLinkedSchemeAfterFetchingHistory() {
        when(holdingRepository.findDistinctAmfiSchemeCodes()).thenReturn(List.of("111111", "222222"));

        scheduler.refreshHeldSchemeHistory();

        verify(navHistoryService).fetchAndStoreHistory("111111");
        verify(navHistoryService).syncHoldingValuations("111111");
        verify(navHistoryService).fetchAndStoreHistory("222222");
        verify(navHistoryService).syncHoldingValuations("222222");
    }

    @Test
    void oneSchemeFailingDoesNotAbortTheRestOfTheRun() {
        when(holdingRepository.findDistinctAmfiSchemeCodes()).thenReturn(List.of("bad", "good"));
        when(navHistoryService.fetchAndStoreHistory("bad")).thenThrow(new RuntimeException("upstream down"));

        scheduler.refreshHeldSchemeHistory();

        verify(navHistoryService).fetchAndStoreHistory("good");
        verify(navHistoryService).syncHoldingValuations("good");
    }
}
