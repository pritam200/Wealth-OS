package com.marketai.mf.service;

import com.marketai.mf.repository.MfNavHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

class MfPerformanceReuseTest {

    @Test
    @DisplayName("A fund's figures are read once and reused until new NAVs are stored for it")
    void reusedUntilForgotten() {
        MfNavHistoryRepository repo = mock(MfNavHistoryRepository.class);
        when(repo.findBySchemeCodeOrderByDateAsc("1")).thenReturn(List.of());
        MfPerformanceService service = new MfPerformanceService(repo);
        ReflectionTestUtils.setField(service, "reuse", true);

        service.getPerformance("1");
        service.getPerformance("1");
        verify(repo, times(1)).findBySchemeCodeOrderByDateAsc("1");

        service.forget("1");
        service.getPerformance("1");
        verify(repo, times(2)).findBySchemeCodeOrderByDateAsc("1");
    }
}
