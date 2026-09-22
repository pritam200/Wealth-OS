package com.marketai.ai.service;

import com.marketai.ai.dto.AiRequest;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.repository.AiHistoryRepository;
import com.marketai.auth.entity.User;
import com.marketai.market.service.MarketDataService;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GeminiClient used to RETURN the sentence "AI features require a Gemini API key…" instead of
 * failing, and AiService had no way to tell it apart from analysis: it persisted that sentence
 * to ai_history and served it as the assistant's answer. "No model configured" is not a fact
 * about the market, so it must be an error — that is what these tests pin down.
 */
class AiServiceNoModelTest {

    private LlmProviderRouter llm;
    private AiHistoryRepository historyRepository;
    private AiService service;
    private User user;

    @BeforeEach
    void setUp() {
        llm = mock(LlmProviderRouter.class);
        historyRepository = mock(AiHistoryRepository.class);
        service = new AiService(llm, historyRepository,
            mock(MarketDataService.class), mock(TechnicalIndicatorService.class), mock(PortfolioService.class));
        user = User.builder().id(1L).email("someone@example.com").build();

        when(llm.completeProse(anyString(), anyString()))
            .thenThrow(new LlmUnavailableException("No LLM provider is available"));
    }

    @Test
    @DisplayName("chat fails with 503 when no model is available, instead of answering with the setup instructions")
    void chatFailsWhenNoModel() {
        AiRequest request = new AiRequest();
        request.setPrompt("Why is Nifty falling today?");

        assertThatThrownBy(() -> service.chat(request, user))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("nothing is written to ai_history when the model never answered")
    void noHistoryRowWhenNoModel() {
        AiRequest request = new AiRequest();
        request.setPrompt("Review my portfolio");

        assertThatThrownBy(() -> service.chat(request, user)).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(historyRepository);
    }

    @Test
    @DisplayName("the market summary path fails the same way — no silent placeholder answer")
    void marketSummaryFailsWhenNoModel() {
        assertThatThrownBy(() -> service.getMarketSummary(user))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(historyRepository);
    }
}
