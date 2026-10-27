package com.marketai.gmail.repository;

import com.marketai.gmail.entity.SavedPdfPassword;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SavedPdfPasswordLookupTest {

    @Test
    @DisplayName("a sender with one saved password per document type gives the latest, instead of failing the sync")
    void twoPasswordsForOneSender() {
        SavedPdfPasswordRepository repo = mock(SavedPdfPasswordRepository.class, CALLS_REAL_METHODS);
        SavedPdfPassword latest = SavedPdfPassword.builder().id(2L).providerKey("mstock.com").documentType("CONTRACT_NOTE").build();
        SavedPdfPassword older = SavedPdfPassword.builder().id(1L).providerKey("mstock.com").build();
        doReturn(List.of(latest, older)).when(repo).findAllByUserIdAndProviderKeyOrderByUpdatedAtDesc(2L, "mstock.com");

        assertThat(repo.findByUserIdAndProviderKey(2L, "mstock.com")).contains(latest);
    }
}
