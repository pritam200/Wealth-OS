package com.marketai.ai.service;

import com.marketai.ai.client.GeminiClient;
import com.marketai.ai.dto.AiRequest;
import com.marketai.ai.dto.AiResponse;
import com.marketai.ai.entity.AiHistory;
import com.marketai.ai.repository.AiHistoryRepository;
import com.marketai.auth.entity.User;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.service.MarketDataService;
import com.marketai.portfolio.dto.PortfolioSummaryDto;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final GeminiClient geminiClient;
    private final AiHistoryRepository aiHistoryRepository;
    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technicalService;
    private final PortfolioService portfolioService;

    private static final String SYSTEM_INSTRUCTION =
            "You are a professional Indian stock market analyst with deep expertise in NSE/BSE markets, " +
            "Indian economy, sectoral analysis, and technical analysis. " +
            "Provide concise, data-driven insights tailored for Indian retail investors. " +
            "Always mention risks alongside opportunities. Use INR for monetary values. " +
            "Be specific and actionable. Format your response as plain text without markdown.";

    @Transactional
    public AiResponse analyseStock(AiRequest request, User user) {
        String symbol = request.getSymbol();
        StringBuilder context = new StringBuilder();
        context.append("Stock: ").append(symbol).append("\n\n");

        try {
            QuoteDto quote = marketDataService.getQuote(symbol);
            context.append("Current Price: ₹").append(quote.getCurrentPrice()).append("\n");
            context.append("Day Change: ").append(quote.getChangePercent()).append("%\n");
            context.append("52W High: ₹").append(quote.getWeekHigh52()).append("\n");
            context.append("52W Low: ₹").append(quote.getWeekLow52()).append("\n\n");

            TechnicalAnalysisDto tech = technicalService.analyse(symbol);
            context.append("Technical Analysis:\n");
            context.append("RSI(14): ").append(tech.getRsi()).append("\n");
            context.append("MACD: ").append(tech.getMacd())
                   .append(" | Signal: ").append(tech.getMacdSignal()).append("\n");
            context.append("Trend: ").append(tech.getTrend()).append("\n");
            context.append("Signal: ").append(tech.getSignal()).append("\n");
            context.append("Support: ₹").append(tech.getSupport())
                   .append(" | Resistance: ₹").append(tech.getResistance()).append("\n");
        } catch (Exception e) {
            log.warn("Could not fetch context for {}: {}", symbol, e.getMessage());
        }

        String prompt = context + "\nUser question: " + request.getPrompt();
        String rawResponse = geminiClient.generateContent(SYSTEM_INSTRUCTION, prompt);

        saveHistory(user, AiHistory.QueryType.STOCK_ANALYSIS, request.getPrompt(), rawResponse, symbol);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional
    public AiResponse getMarketSummary(User user) {
        String context = "Provide a comprehensive market summary for the Indian stock market today. " +
                "Cover Nifty 50, Bank Nifty, sector performance, FII/DII activity, " +
                "key movers and shakers, and the overall market sentiment.";

        String rawResponse = geminiClient.generateContent(SYSTEM_INSTRUCTION, context);
        saveHistory(user, AiHistory.QueryType.MARKET_SUMMARY, context, rawResponse, null);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
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
        } catch (Exception e) {
            context.append("Could not load portfolio data.\n");
        }

        context.append("\nProvide: diversification analysis, underperformers to review, " +
                "risk assessment, rebalancing suggestions for Indian market conditions.");

        String rawResponse = geminiClient.generateContent(SYSTEM_INSTRUCTION, context.toString());
        saveHistory(user, AiHistory.QueryType.PORTFOLIO_REVIEW, "Portfolio review", rawResponse, null);

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional
    public AiResponse chat(AiRequest request, User user) {
        String rawResponse = geminiClient.generateContent(SYSTEM_INSTRUCTION, request.getPrompt());
        saveHistory(user, AiHistory.QueryType.CHAT, request.getPrompt(), rawResponse,
                request.getSymbol());

        return AiResponse.builder()
                .summary(rawResponse)
                .rawResponse(rawResponse)
                .generatedAt(LocalDateTime.now())
                .build();
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
