package com.marketai.advisor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.advisor.dto.AdvisorAskRequest;
import com.marketai.advisor.dto.AdvisorAskResponse;
import com.marketai.advisor.dto.AdvisorTool;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.expense.dto.ExpenseResponse;
import com.marketai.expense.service.ExpenseService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.reminder.dto.ReminderResponse;
import com.marketai.reminder.service.ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Ask your data" chat layer. Every answer is built entirely from real numbers already
 * computed by {@link PortfolioContextService}, {@link ExpenseService} or {@link ReminderService}
 * — the LLM's ONLY job is picking which one of those to call ({@link AdvisorTool}), via
 * {@link #classify}. The final answer text is assembled by this class from the tool's result,
 * never handed to the model to phrase, so there is no path by which a figure the model invents
 * can reach the user. When no model is available the classify step can't run at all, so the
 * whole feature degrades to a clear "unavailable" answer rather than guessing a tool.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdvisorService {

    private final LlmProviderRouter llm;
    private final LlmJsonParser json;
    private final AiAuditService audit;
    private final PortfolioContextService portfolioContextService;
    private final ExpenseService expenseService;
    private final ReminderService reminderService;

    private static final String TASK = "ADVISOR_ASK";

    private static final String CLASSIFY_SYSTEM =
        "You route a personal-finance question to exactly one read-only data tool. Return ONLY " +
        "a JSON object, no prose, no markdown fences: {\"tool\": one of NET_WORTH|" +
        "RECENT_EXPENSES|UPCOMING_REMINDERS|PORTFOLIO_HOLDINGS|UNKNOWN}. " +
        "NET_WORTH = total assets, net worth, asset allocation. " +
        "RECENT_EXPENSES = recent spending, expenses by category, how much was spent. " +
        "UPCOMING_REMINDERS = upcoming bills, EMIs, FD/RD maturities, SIP due dates. " +
        "PORTFOLIO_HOLDINGS = stock/mutual-fund holdings, positions, portfolio value. " +
        "Use UNKNOWN when the question does not clearly match one of the above — never guess.";

    public AdvisorAskResponse ask(Long userId, AdvisorAskRequest request) {
        String question = request.getQuestion();

        if (!llm.isEnabled()) {
            audit.recordFailure(userId, TASK, null, CLASSIFY_SYSTEM, question,
                "UNAVAILABLE", "No LLM provider configured");
            return unavailable();
        }

        AdvisorTool tool;
        try {
            LlmCompletion completion = llm.complete(CLASSIFY_SYSTEM, question);
            tool = parseTool(completion);
            audit.record(userId, TASK, null, CLASSIFY_SYSTEM, question, completion, null,
                "CLASSIFIED", "tool=" + tool);
        } catch (LlmUnavailableException e) {
            log.warn("Advisor classify rejected — no model available: {}", e.getMessage());
            audit.recordFailure(userId, TASK, null, CLASSIFY_SYSTEM, question,
                "UNAVAILABLE", e.getMessage());
            return unavailable();
        }

        return switch (tool) {
            case NET_WORTH -> answerNetWorth(userId);
            case RECENT_EXPENSES -> answerRecentExpenses(userId);
            case UPCOMING_REMINDERS -> answerUpcomingReminders(userId);
            case PORTFOLIO_HOLDINGS -> answerPortfolioHoldings(userId);
            default -> AdvisorAskResponse.builder()
                .answer("I can answer questions about your net worth, recent expenses, "
                    + "upcoming reminders, or portfolio holdings — try rephrasing around one of those.")
                .tool(AdvisorTool.UNKNOWN)
                .groundedData(Map.of())
                .available(true)
                .generatedAt(LocalDateTime.now())
                .build();
        };
    }

    private AdvisorTool parseTool(LlmCompletion completion) {
        return json.parse(completion.getText())
            .map(n -> json.str(n, "tool"))
            .map(s -> {
                try { return AdvisorTool.valueOf(s.trim().toUpperCase()); }
                catch (IllegalArgumentException e) { return AdvisorTool.UNKNOWN; }
            })
            .orElse(AdvisorTool.UNKNOWN);
    }

    private AdvisorAskResponse unavailable() {
        return AdvisorAskResponse.builder()
            .answer(LlmProviderRouter.NONE_AVAILABLE)
            .tool(AdvisorTool.UNKNOWN)
            .groundedData(Map.of())
            .available(false)
            .generatedAt(LocalDateTime.now())
            .build();
    }

    private AdvisorAskResponse answerNetWorth(Long userId) {
        PortfolioContext ctx = portfolioContextService.build(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("netWorth", ctx.getNetWorth());
        data.put("totalAssets", ctx.getTotalAssets());
        data.put("equityPercent", ctx.getEquityPercent());
        data.put("debtPercent", ctx.getDebtPercent());
        data.put("dataQuality", ctx.getDataQuality());

        StringBuilder sb = new StringBuilder();
        sb.append("Your net worth is ₹").append(plain(ctx.getNetWorth()))
          .append(" across ₹").append(plain(ctx.getTotalAssets())).append(" of total assets.");
        if (ctx.getEquityPercent() != null) {
            sb.append(" Roughly ").append(ctx.getEquityPercent()).append("% is in equity");
            if (ctx.getDebtPercent() != null) sb.append(" and ").append(ctx.getDebtPercent()).append("% in debt");
            sb.append(".");
        }
        if (!ctx.getDataGaps().isEmpty()) {
            sb.append(" Note: ").append(ctx.getDataGaps().get(0));
        }
        return AdvisorAskResponse.builder()
            .answer(sb.toString()).tool(AdvisorTool.NET_WORTH).groundedData(data)
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private AdvisorAskResponse answerRecentExpenses(Long userId) {
        List<ExpenseResponse> expenses = expenseService.listExpenses(userId);
        BigDecimal total = expenses.stream().map(ExpenseResponse::getAmount)
            .filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (ExpenseResponse e : expenses) {
            if (e.getAmount() == null) continue;
            byCategory.merge(e.getCategory() == null ? "Uncategorised" : e.getCategory(),
                e.getAmount(), BigDecimal::add);
        }
        List<Map.Entry<String, BigDecimal>> topCategories = byCategory.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(3).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalLast90Days", total);
        data.put("expenseCount", expenses.size());
        data.put("byCategory", byCategory);

        StringBuilder sb = new StringBuilder();
        sb.append("You've spent ₹").append(plain(total)).append(" across ")
          .append(expenses.size()).append(" transactions in the last 90 days.");
        if (!topCategories.isEmpty()) {
            sb.append(" Top categories: ");
            sb.append(String.join(", ", topCategories.stream()
                .map(e -> e.getKey() + " (₹" + plain(e.getValue()) + ")").toList()));
            sb.append(".");
        }
        return AdvisorAskResponse.builder()
            .answer(sb.toString()).tool(AdvisorTool.RECENT_EXPENSES).groundedData(data)
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private AdvisorAskResponse answerUpcomingReminders(Long userId) {
        List<ReminderResponse> reminders = reminderService.getReminders(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", reminders.size());
        data.put("reminders", reminders);

        StringBuilder sb = new StringBuilder();
        if (reminders.isEmpty()) {
            sb.append("You have no upcoming bills, EMIs or maturities in the tracked window.");
        } else {
            sb.append("You have ").append(reminders.size()).append(" upcoming item")
              .append(reminders.size() == 1 ? "" : "s").append(": ");
            sb.append(String.join("; ", reminders.stream().limit(5)
                .map(r -> r.getTitle() + " due " + r.getDueDate()
                    + (r.getAmount() != null ? " (₹" + plain(r.getAmount()) + ")" : ""))
                .toList()));
            sb.append(".");
        }
        return AdvisorAskResponse.builder()
            .answer(sb.toString()).tool(AdvisorTool.UPCOMING_REMINDERS).groundedData(data)
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private AdvisorAskResponse answerPortfolioHoldings(Long userId) {
        List<Holding> holdings = portfolioContextService.getAllHoldings(userId);
        BigDecimal totalValue = holdings.stream().map(Holding::getCurrentValue)
            .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Holding> top = holdings.stream()
            .filter(h -> h.getCurrentValue() != null)
            .sorted(Comparator.comparing(Holding::getCurrentValue, Comparator.reverseOrder()))
            .limit(5).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("holdingCount", holdings.size());
        data.put("totalValue", totalValue);
        data.put("topHoldings", top.stream()
            .map(h -> Map.of("symbol", (Object) h.getSymbol(), "currentValue", h.getCurrentValue()))
            .toList());

        StringBuilder sb = new StringBuilder();
        if (holdings.isEmpty()) {
            sb.append("You have no holdings on record yet.");
        } else {
            sb.append("You hold ").append(holdings.size()).append(" position")
              .append(holdings.size() == 1 ? "" : "s").append(" worth ₹").append(plain(totalValue)).append(" in total.");
            if (!top.isEmpty()) {
                sb.append(" Largest: ").append(String.join(", ", top.stream()
                    .map(h -> h.getSymbol() + " (₹" + plain(h.getCurrentValue()) + ")").toList()));
                sb.append(".");
            }
        }
        return AdvisorAskResponse.builder()
            .answer(sb.toString()).tool(AdvisorTool.PORTFOLIO_HOLDINGS).groundedData(data)
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private String plain(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }
}
