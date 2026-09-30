package com.marketai.ai.service;

import com.marketai.ai.prompt.PromptLibrary;

import com.marketai.ai.llm.LlmTask;

import com.marketai.ai.llm.LlmService;

import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.dto.AiRequest;
import com.marketai.ai.dto.AiResponse;
import com.marketai.ai.entity.AiHistory;
import com.marketai.ai.repository.AiHistoryRepository;
import com.marketai.auth.entity.User;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.service.MarketDataService;
import com.marketai.portfolio.dto.PortfolioSummaryDto;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.research.model.ResearchResult;
import com.marketai.research.service.NumberGuard;
import com.marketai.research.service.ResearchOrchestrator;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The free-form AI analyst behind /api/ai/*. Goes through {@link LlmService}, so it
 * runs on the local Ollama model by default (no key, no cost, nothing leaves the machine)
 * and on Gemini only when explicitly configured — it used to call GeminiClient directly,
 * which made app.llm.provider=none a lie for this feature.
 *
 * When no model is available the request FAILS (503). It must never persist a
 * "configure your API key" sentence into ai_history as though the model had answered.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final LlmService llm;
    private final AiHistoryRepository aiHistoryRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technicalService;
    private final PortfolioService portfolioService;
    private final ResearchOrchestrator research;

    
    @Transactional
    public AiResponse analyseStock(AiRequest request, User user) {
        String symbol = request.getSymbol();
        // The same verified context and stored research the stock page shows (one engine); the
        // model answers the question from it and every figure in the answer is checked against it.
        ResearchResult rr = research.stock(symbol, null, user != null ? user.getId() : null, ResearchOrchestrator.Mode.CACHED);
        StringBuilder context = new StringBuilder(ResearchOrchestrator.render(rr));
        if (rr.getReport() != null) {
            context.append("\n\nSTORED RESEARCH (").append(rr.getResearchTimestamp()).append(rr.isStale() ? ", made on older data" : "").append("):\n")
                   .append("Summary: ").append(rr.getReport().getExecutiveSummary().text()).append('\n')
                   .append("Conclusion: ").append(rr.getReport().getResearchConclusion().text()).append('\n');
        }
        if (rr.getFinalView() != null) {
            context.append("Final view (code-decided): ").append(rr.getFinalView().actionability()).append(" — ").append(rr.getFinalView().reason()).append('\n');
        }

        String prompt = context + "\nUser question: " + request.getPrompt();
        String rawResponse = new NumberGuard(rr.getFacts(), rr.getEvidence()).clean(ask(prompt));

        saveHistory(user, AiHistory.QueryType.STOCK_ANALYSIS, request.getPrompt(), rawResponse, symbol);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional
    public AiResponse getMarketSummary(User user) {
        // The model used to be asked for "Nifty, Bank Nifty, FII/DII activity, key movers" with no
        // data at all, so every figure in the answer was invented. It now summarises only what
        // the app has fetched, with the time it was fetched.
        StringBuilder context = new StringBuilder("Summarise today's Indian market using ONLY the data below. ")
                .append("FII/DII flows and individual top movers are not provided — say they are unavailable rather than naming any.\n\n");
        try {
            com.marketai.market.dto.MarketOverviewDto o = marketDataService.getMarketOverview();
            context.append("Fetched at ").append(o.getLastUpdated()).append(" IST (Yahoo Finance, delayed).\n");
            for (com.marketai.market.dto.MarketOverviewDto.IndexQuote iq : java.util.Arrays.asList(o.getNifty50(), o.getBankNifty(), o.getSensex(), o.getNiftyMidcap())) {
                if (iq == null) continue;
                context.append(iq.getName()).append(": ").append(iq.getValue() != null ? iq.getValue() : "unavailable")
                       .append(iq.getChangePercent() != null ? " (" + iq.getChangePercent() + "%)" : "").append("\n");
            }
            if (o.getSectors() != null && !o.getSectors().isEmpty()) {
                context.append("Sector indices, % change today: ");
                o.getSectors().forEach(sp -> context.append(sp.getSector()).append(" ").append(sp.getChangePercent()).append("%; "));
                context.append("\n");
            }
        } catch (Exception e) {
            log.warn("Market overview unavailable for AI summary: {}", e.getMessage());
            context.append("Market data could not be loaded — reply only that the summary is unavailable.\n");
        }

        String rawResponse = ask(context.toString());
        saveHistory(user, AiHistory.QueryType.MARKET_SUMMARY, "Market summary", rawResponse, null);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private static String orNa(Object v) {
        return v == null ? "unavailable" : v.toString();
    }

    @Transactional
    public AiResponse reviewPortfolio(Long portfolioId, User user) {
        StringBuilder context = new StringBuilder("Portfolio Review Request\n\n");

        try {
            PortfolioSummaryDto summary = portfolioService.getPortfolioSummary(portfolioId, user.getId());
            context.append("Portfolio: ").append(summary.getName()).append("\n");
            context.append("Total Invested: ₹").append(summary.getTotalInvested()).append("\n");
            context.append("Current Value: ₹").append(summary.getCurrentValue()).append("\n");
            context.append("Total P&L: ₹").append(summary.getTotalPnl())
                   .append(" (").append(summary.getTotalPnlPercent()).append("%)\n\n");

            context.append("Holdings:\n");
            if (summary.getHoldings() != null) {
                summary.getHoldings().forEach(h ->
                        context.append("- ").append(h.getSymbol())
                               .append(": ₹").append(h.getCurrentValue())
                               .append(" | P&L: ").append(h.getPnlPercent()).append("%\n")
                );
            }
        } catch (com.marketai.common.exception.ResourceNotFoundException e) {
            // Let this through as a 404. Swallowing it meant a request for a portfolio that isn't
            // yours returned 200 with an AI-written "review" of nothing, and made a wrong id
            // indistinguishable from a database failure.
            throw e;
        } catch (Exception e) {
            // Anything else is a genuine transient failure. Say so in the prompt rather than
            // letting the model fill the gap, and log it — the exception used to be discarded.
            log.warn("Portfolio {} data could not be loaded for AI review: {}", portfolioId, e.getMessage());
            context.append("Could not load portfolio data — do not speculate about holdings.\n");
        }

        context.append("\nProvide: diversification analysis, underperformers to review, " +
                "risk assessment, rebalancing suggestions for Indian market conditions.");

        String rawResponse = ask(context.toString());
        saveHistory(user, AiHistory.QueryType.PORTFOLIO_REVIEW, "Portfolio review", rawResponse, null);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional
    public AiResponse chat(AiRequest request, User user) {
        String rawResponse = ask(request.getPrompt());
        saveHistory(user, AiHistory.QueryType.CHAT, request.getPrompt(), rawResponse,
                request.getSymbol());

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * One prose call through the router. A missing/unreachable model becomes a 503 rather
     * than a string that reads like an answer, so nothing downstream (history, the UI) can
     * mistake "no model configured" for analysis.
     */
    private String ask(String prompt) {
        try {
            return llm.complete(LlmTask.GENERAL_ASSISTANT, PromptLibrary.GENERAL_ANALYST, prompt).getText();
        } catch (LlmUnavailableException e) {
            log.warn("AI request rejected — no model available: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, LlmService.NONE_AVAILABLE);
        }
    }

    private void saveHistory(User user, AiHistory.QueryType type, String prompt,
                              String response, String symbol) {
        AiHistory history = AiHistory.builder()
                .user(user)
                .queryType(type)
                .prompt(prompt)
                .response(response)
                .relatedSymbol(symbol)
                .build();
        aiHistoryRepository.save(history);
    }
}
