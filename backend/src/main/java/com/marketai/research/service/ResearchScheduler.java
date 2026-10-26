package com.marketai.research.service;

import com.marketai.ai.llm.LlmService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.research.model.ResearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Researches the market and the stocks people hold once each trading day, after the close, so
 * Today's Actions, recommendations and the analyst panel — which only read stored research —
 * have the day's research before anyone opens each stock page.
 *
 * <p>Off unless {@code app.research.scheduler.enabled} is set: each subject is three model
 * calls on the free Gemini tier, which the mail sync also uses. Subjects already researched for
 * the session are skipped without a call; the rest are paced, capped per run, and the run stops
 * as soon as the quota is spent or a call is refused.
 */
@Component
@Slf4j
public class ResearchScheduler {

    private final ResearchOrchestrator research;
    private final LlmService llm;
    private final PortfolioRepository portfolios;
    private final HoldingRepository holdings;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int maxSubjects;
    private final long paceMs;

    public ResearchScheduler(ResearchOrchestrator research, LlmService llm, PortfolioRepository portfolios,
                             HoldingRepository holdings, TransactionTemplate tx,
                             @Value("${app.research.scheduler.enabled:false}") boolean enabled,
                             @Value("${app.research.scheduler.max-subjects:10}") int maxSubjects,
                             @Value("${app.research.scheduler.pace-seconds:60}") long paceSeconds) {
        this.research = research;
        this.llm = llm;
        this.portfolios = portfolios;
        this.holdings = holdings;
        this.tx = tx;
        this.enabled = enabled;
        this.maxSubjects = maxSubjects;
        this.paceMs = paceSeconds * 1000;
    }

    record Subject(Long userId, String symbol, String name) {}

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void daily() {
        if (!enabled) return;
        run();
    }

    /** One pass: the market first, then each held stock. Returns how many subjects were researched afresh. */
    int run() {
        List<Subject> subjects = new ArrayList<>();
        subjects.add(new Subject(null, "^NSEI", "Nifty 50"));
        subjects.addAll(heldStocks());
        int ran = 0;
        for (Subject s : subjects) {
            if (ran >= maxSubjects) {
                log.info("Scheduled research stopped at its cap of {} subjects", maxSubjects);
                break;
            }
            if (llm.quotaSpentUntil(ResearchOrchestrator.TASK).isPresent()) {
                log.info("Scheduled research stopped: the Gemini daily quota is spent");
                break;
            }
            ResearchResult r;
            try {
                r = s.userId() == null
                        ? research.index(s.symbol(), s.name(), ResearchOrchestrator.Mode.AUTO)
                        : research.stock(s.symbol(), s.name(), s.userId(), ResearchOrchestrator.Mode.AUTO);
            } catch (Exception e) {
                log.warn("Scheduled research for {} failed: {}", s.symbol(), e.getClass().getSimpleName());
                continue;
            }
            if (r.isFromCache()) continue;
            ran++;
            if ("UNAVAILABLE".equals(r.getStatus()) || "DISABLED".equals(r.getStatus())) {
                log.info("Scheduled research stopped at {}: {}", s.symbol(), r.getStatusReason());
                break;
            }
            pause();
        }
        if (ran > 0) log.info("Scheduled research ran for {} subject(s)", ran);
        return ran;
    }

    /** Each user's open stock holdings (funds have no daily research to refresh). */
    List<Subject> heldStocks() {
        List<Subject> out = tx.execute(status -> {
            Set<String> seen = new LinkedHashSet<>();
            List<Subject> found = new ArrayList<>();
            for (Portfolio p : portfolios.findAll()) {
                Long userId = p.getUser() != null ? p.getUser().getId() : null;
                if (userId == null) continue;
                for (Holding h : holdings.findOpenByPortfolioId(p.getId())) {
                    String sym = h.getSymbol();
                    if (sym == null || sym.endsWith(".MF") || !seen.add(userId + "|" + sym.replace(".NS", ""))) continue;
                    found.add(new Subject(userId, sym, h.getName()));
                }
            }
            return found;
        });
        return out != null ? out : List.of();
    }

    private void pause() {
        if (paceMs <= 0) return;
        try {
            Thread.sleep(paceMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
