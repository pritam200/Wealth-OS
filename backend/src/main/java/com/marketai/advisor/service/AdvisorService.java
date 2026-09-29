package com.marketai.advisor.service;

import com.marketai.ai.prompt.PromptLibrary;

import com.marketai.ai.llm.LlmTask;

import com.marketai.ai.llm.LlmService;

import com.marketai.advisor.dto.AdvisorAskRequest;
import com.marketai.advisor.dto.AdvisorAskResponse;
import com.marketai.advisor.dto.AdvisorTool;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
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
import java.time.LocalDate;
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

    private final LlmService llm;
    private final LlmJsonParser json;
    private final AiAuditService audit;
    private final PortfolioContextService portfolioContextService;
    private final ExpenseService expenseService;
    private final ReminderService reminderService;
    private final LedgerInvestigator investigator;

    private static final String TASK = "ADVISOR_ASK";

    
    /** Words that name an asset class, not a provider — dropped from a filter ("HDFC MF" → "HDFC"). */
    private static final java.util.Set<String> GENERIC_WORDS = java.util.Set.of(
        "mf", "mfs", "mutual", "fund", "funds", "stock", "stocks", "share", "shares", "equity", "portfolio",
        "investments", "investment", "holding", "holdings", "all", "email", "emails", "gmail", "my", "value");

    public AdvisorAskResponse ask(Long userId, AdvisorAskRequest request) {
        String question = request.getQuestion();

        if (!llm.isAvailable(LlmTask.AI_ADVISOR)) {
            audit.recordFailure(userId, TASK, null, PromptLibrary.ADVISOR_ROUTING.system(), question,
                "UNAVAILABLE", "No LLM provider configured");
            return unavailable();
        }

        Route route;
        try {
            LlmCompletion completion = llm.complete(LlmTask.AI_ADVISOR, PromptLibrary.ADVISOR_ROUTING, question);
            route = parseRoute(completion, question);
            audit.record(userId, TASK, null, PromptLibrary.ADVISOR_ROUTING.system(), question, completion, null,
                "CLASSIFIED", "tool=" + route.tool() + (route.subject() == null ? "" : ", subject=" + route.subject()));
        } catch (LlmUnavailableException e) {
            log.warn("Advisor classify rejected — no model available: {}", e.getMessage());
            audit.recordFailure(userId, TASK, null, PromptLibrary.ADVISOR_ROUTING.system(), question,
                "UNAVAILABLE", e.getMessage());
            return unavailable();
        }

        LocalDate today = LocalDate.now();
        return switch (route.tool()) {
            case NET_WORTH -> answerNetWorth(userId);
            case RECENT_EXPENSES -> answerRecentExpenses(userId);
            case UPCOMING_REMINDERS -> answerUpcomingReminders(userId);
            case PORTFOLIO_HOLDINGS -> answerPortfolioHoldings(userId);
            case NET_WORTH_CHANGE -> grounded(route.tool(), investigator.netWorthChange(userId, today));
            case INVESTMENT_PLAN -> grounded(route.tool(), investigator.investmentPlan(userId, today));
            case IMPORTED_TRANSACTIONS -> grounded(route.tool(), investigator.importedTransactions(userId, route.subject(), today));
            case VALUE_CHANGE -> grounded(route.tool(), investigator.valueChange(userId, route.subject(), route.scope()));
            case DUPLICATES -> grounded(route.tool(), investigator.duplicates(userId));
            case MISSING_TRANSACTIONS -> grounded(route.tool(), investigator.missingTransactions(userId));
            case HOLDING_SOURCES -> grounded(route.tool(), investigator.holdingSources(userId, route.subject(), route.scope()));
            case UNKNOWN -> AdvisorAskResponse.builder()
                .answer("I can answer questions about your net worth and how it changed, spending, upcoming "
                    + "reminders, holdings and the documents behind them, this month's investment plan, imported "
                    + "transactions, fund value changes, duplicates and missing records — try rephrasing around one of those.")
                .tool(AdvisorTool.UNKNOWN)
                .groundedData(Map.of())
                .available(true)
                .generatedAt(LocalDateTime.now())
                .build();
        };
    }

    /** What the model picked: a tool and, for some tools, a filter taken from the question. */
    record Route(AdvisorTool tool, String subject, LedgerInvestigator.Scope scope) {}

    /**
     * The subject is only a filter, and only kept when it is actually in the question — so the
     * model can narrow an answer to what the user named but can't invent a provider to look up.
     */
    Route parseRoute(LlmCompletion completion, String question) {
        var node = json.parse(completion.getText());
        AdvisorTool tool = node.map(n -> json.str(n, "tool")).map(s -> {
            try { return AdvisorTool.valueOf(s.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return AdvisorTool.UNKNOWN; }
        }).orElse(AdvisorTool.UNKNOWN);
        String subject = node.map(n -> json.str(n, "subject")).map(s -> cleanSubject(s, question)).orElse(null);
        LedgerInvestigator.Scope scope = node.map(n -> json.str(n, "scope")).map(s -> {
            try { return LedgerInvestigator.Scope.valueOf(s.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return LedgerInvestigator.Scope.ALL; }
        }).orElse(LedgerInvestigator.Scope.ALL);
        return new Route(tool, subject, scope);
    }

    static String cleanSubject(String raw, String question) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^\\p{L}\\p{N} &.\\-]", " ").trim().replaceAll("\\s+", " ");
        if (s.isEmpty() || s.length() > 40 || s.equalsIgnoreCase("null")) return null;
        if (question == null || !question.toLowerCase(java.util.Locale.ROOT).contains(s.toLowerCase(java.util.Locale.ROOT))) return null;
        String kept = java.util.Arrays.stream(s.split(" "))
            .filter(w -> !GENERIC_WORDS.contains(w.toLowerCase(java.util.Locale.ROOT)))
            .collect(java.util.stream.Collectors.joining(" "));
        return kept.isEmpty() ? null : kept;
    }

    private AdvisorAskResponse grounded(AdvisorTool tool, LedgerInvestigator.Answer a) {
        return AdvisorAskResponse.builder()
            .answer(a.text()).tool(tool).groundedData(a.data()).evidence(a.evidence())
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private AdvisorAskResponse unavailable() {
        return AdvisorAskResponse.builder()
            .answer(LlmService.NONE_AVAILABLE)
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
        sb.append(LedgerInvestigator.gapNote(ctx));
        return AdvisorAskResponse.builder()
            .answer(sb.toString()).tool(AdvisorTool.NET_WORTH).groundedData(data)
            .available(true).generatedAt(LocalDateTime.now()).build();
    }

    private AdvisorAskResponse answerRecentExpenses(Long userId) {
        // Card-bill payments and self transfers move money that was already spent or is still
        // the user's; counting them double-counts every card purchase. Same exclusion as the
        // app's other spend totals.
        List<ExpenseResponse> expenses = expenseService.listExpenses(userId).stream()
            .filter(e -> !com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER.getLabel().equals(e.getCategory())
                && !com.marketai.expense.entity.ExpenseCategory.INVESTMENT.getLabel().equals(e.getCategory()))
            .toList();
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
