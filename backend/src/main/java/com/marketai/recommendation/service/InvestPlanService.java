package com.marketai.recommendation.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.dto.MfCategoryBucket;
import com.marketai.amfi.service.AmfiNavService;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.mf.service.MfPeerComparisonService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.recommendation.dto.PortfolioContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a sum of savings could go, by fixed rules the answer states: clear card dues first, then
 * top up an emergency fund of six months' spending, then split the rest by a target mix for the
 * horizon and risk chosen — steering the money to whatever the portfolio is short of — and
 * naming the user's own funds in each part, strongest record first. Not investment advice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestPlanService {

    static final int EMERGENCY_MONTHS = 6;
    /** Above this, money going into equity is staggered rather than invested on one day. */
    static final BigDecimal STAGGER_ABOVE = new BigDecimal("100000");
    public static final String DISCLAIMER = "A rule-based split from the figures on record, not investment advice. "
        + "Check each fund's scheme documents, exit load and your own needs before investing.";

    public enum Risk { CONSERVATIVE, MODERATE, AGGRESSIVE }

    /** The parts new money can go to. Equity is split by market cap. */
    public enum Part {
        EMERGENCY("Emergency fund", "Liquid fund or sweep-in FD"),
        CARD_DUES("Credit card dues", "Pay the card bill"),
        EQUITY_CORE("Equity — large cap / index", "Nifty 50 or large-cap fund"),
        EQUITY_FLEXI("Equity — flexi / multi cap", "Flexi-cap fund"),
        EQUITY_MIDSMALL("Equity — mid & small cap", "Mid- or small-cap fund"),
        DEBT("Debt", "Short-duration debt fund or FD"),
        GOLD("Gold", "Gold fund or ETF");

        public final String label, where;
        Part(String label, String where) { this.label = label; this.where = where; }
    }

    public record FundPick(String name, String schemeCode, Double return3Y, Double return1Y, BigDecimal held) {}
    /** A fund not held yet, among the strongest in its category on its own record. */
    public record Idea(String name, String schemeCode, Double return3Y, Double return1Y, Double volatility, Double worstFall, String why) {}
    public record Step(Part part, String label, BigDecimal amount, double percent, String where, String why, List<FundPick> funds,
                       List<Idea> ideas, String ideasNote) {
        Step withIdeas(List<Idea> i, String note) { return new Step(part, label, amount, percent, where, why, funds, i, note); }
    }
    public record Mix(double equity, double debt, double gold) {}
    public record Plan(BigDecimal amount, int horizonYears, Risk risk, Mix current, Mix target, BigDecimal monthlySpend,
                       List<Step> steps, List<String> notes, String disclaimer) {}

    private final PortfolioContextService contextService;
    private final ExpenseRepository expenses;
    private final AmfiNavService amfi;

    @Autowired(required = false)
    private MfPeerComparisonService peers;

    public Plan suggest(Long userId, BigDecimal amount, int horizonYears, Risk risk) {
        PortfolioContext ctx = contextService.build(userId);
        List<Holding> holdings = contextService.getAllHoldings(userId);
        Map<Part, List<FundPick>> own = ownFunds(holdings);
        BigDecimal spend = monthlySpend(userId);
        BigDecimal liquidFunds = own.getOrDefault(Part.EMERGENCY, List.of()).stream().map(FundPick::held).reduce(BigDecimal.ZERO, BigDecimal::add);
        Holdings h = holdingsByClass(ctx, holdings);
        Plan p = plan(amount, horizonYears, risk, spend, nz(ctx.getCashValue()).add(liquidFunds), nz(ctx.getCardDues()), h, own);
        java.util.Set<String> held = new java.util.HashSet<>();
        own.values().forEach(l -> l.forEach(f -> { if (f.schemeCode() != null) held.add(f.schemeCode()); }));
        List<Step> steps = p.steps().stream().map(st -> withIdeas(st, held)).toList();
        return new Plan(p.amount(), p.horizonYears(), p.risk(), p.current(), p.target(), p.monthlySpend(), steps, p.notes(), p.disclaimer());
    }

    /** The AMFI category each part draws new-fund ideas from, and a word the name must contain (gold). */
    private static final Map<Part, String[]> IDEA_CATEGORY = Map.of(
        Part.EMERGENCY, new String[] { "Debt Scheme - Liquid Fund", null },
        Part.EQUITY_CORE, new String[] { "Equity Scheme - Large Cap Fund", null },
        Part.EQUITY_FLEXI, new String[] { "Equity Scheme - Flexi Cap Fund", null },
        Part.EQUITY_MIDSMALL, new String[] { "Equity Scheme - Mid Cap Fund", null },
        Part.DEBT, new String[] { "Debt Scheme - Short Duration Fund", null },
        Part.GOLD, new String[] { "Other Scheme - FoF Domestic", "gold" });

    /** Up to three funds not already held, strongest 3-year record in the part's category first. */
    private Step withIdeas(Step st, java.util.Set<String> held) {
        String[] cat = IDEA_CATEGORY.get(st.part());
        if (cat == null || peers == null) return st;
        try {
            var found = peers.leaders(cat[0], cat[1], 6);
            if (found.leaders().isEmpty()) {
                return st.withIdeas(List.of(), found.loading()
                    ? "Fund ideas for this part are being worked out from AMFI data — check back in a few minutes."
                    : null);
            }
            List<Idea> ideas = found.leaders().stream()
                .filter(l -> !held.contains(l.schemeCode()))
                .limit(3)
                .map(l -> idea(l, st.part()))
                .toList();
            return st.withIdeas(ideas, "Direct growth plans ranked by their own 3-year return in \"" + cat[0].replaceFirst("^.* - ", "")
                + "\". Past returns are not a promise of future ones — compare expense ratio and exit load before choosing.");
        } catch (Exception e) {
            log.debug("No fund ideas for {}: {}", st.part(), e.getMessage());
            return st;
        }
    }

    private static Idea idea(MfPeerComparisonService.PeerMetrics l, Part part) {
        var m = l.m();
        Double r3 = m.getReturn3YCagr() != null ? m.getReturn3YCagr().doubleValue() : null;
        Double r1 = m.getReturn1Y() != null ? m.getReturn1Y().doubleValue() : null;
        Double vol = m.getAnnualisedVolatility() != null ? m.getAnnualisedVolatility().doubleValue() : null;
        Double fall = m.getMaxDrawdownPercent() != null ? m.getMaxDrawdownPercent().abs().doubleValue() : null;
        StringBuilder why = new StringBuilder();
        if (r3 != null) why.append(String.format("%.1f%% a year over 3 years", r3));
        if (r1 != null) why.append(why.length() > 0 ? ", " : "").append(String.format("%.1f%% in the last year", r1));
        if (fall != null) why.append(String.format("; its worst fall was %.0f%%", fall));
        if (vol != null && part.name().startsWith("EQUITY")) why.append(String.format(", swings about %.0f%% a year", vol));
        return new Idea(l.name(), l.schemeCode(), r3, r1, vol, fall, why.append('.').toString());
    }

    /** What is already held, by asset class, in rupees. */
    record Holdings(BigDecimal equity, BigDecimal debt, BigDecimal gold) {
        BigDecimal total() { return equity.add(debt).add(gold); }
    }

    /** The rules themselves, kept free of the database so they can be tested and read on their own. */
    static Plan plan(BigDecimal amount, int horizonYears, Risk risk, BigDecimal monthlySpend, BigDecimal liquid,
                     BigDecimal cardDues, Holdings held, Map<Part, List<FundPick>> own) {
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        BigDecimal left = amount;

        // 1. Card dues cost 36–42% a year; nothing invested reliably beats that.
        if (cardDues.signum() > 0) {
            BigDecimal pay = cardDues.min(left);
            steps.add(step(Part.CARD_DUES, pay, amount, "Card balances carried over are charged about 3–3.5% a month — "
                + "paying them off is a guaranteed return no fund matches.", List.of()));
            left = left.subtract(pay);
        }

        // 2. Six months of spending kept where it can be reached in a day.
        if (monthlySpend.signum() > 0) {
            BigDecimal target = monthlySpend.multiply(BigDecimal.valueOf(EMERGENCY_MONTHS));
            BigDecimal gap = target.subtract(liquid).max(BigDecimal.ZERO);
            if (gap.signum() > 0 && left.signum() > 0) {
                BigDecimal put = gap.min(left);
                steps.add(step(Part.EMERGENCY, put, amount, String.format("Your spending averages ₹%,.0f a month; %d months is ₹%,.0f, "
                    + "and ₹%,.0f is in bank balances and liquid funds. This closes the gap first.", monthlySpend, EMERGENCY_MONTHS, target, liquid),
                    own.getOrDefault(Part.EMERGENCY, List.of())));
                left = left.subtract(put);
            } else {
                notes.add(String.format("Emergency fund looks covered: ₹%,.0f in bank balances and liquid funds against ₹%,.0f of spending a month.", liquid, monthlySpend));
            }
        } else {
            notes.add("No spending on record, so the emergency fund could not be checked — keep about six months of expenses in a bank or liquid fund before investing.");
        }

        Mix target = targetMix(horizonYears, risk);
        Mix current = held.total().signum() > 0
            ? new Mix(pct(held.equity(), held.total()), pct(held.debt(), held.total()), pct(held.gold(), held.total()))
            : new Mix(0, 0, 0);

        if (left.signum() > 0) {
            // 3. Rebalance with new money: fill the classes furthest below target first, and split
            // any remainder by the target itself, so nothing already held has to be sold.
            BigDecimal after = held.total().add(left);
            Map<String, BigDecimal> gap = new LinkedHashMap<>();
            gap.put("equity", after.multiply(BigDecimal.valueOf(target.equity() / 100)).subtract(held.equity()).max(BigDecimal.ZERO));
            gap.put("debt", after.multiply(BigDecimal.valueOf(target.debt() / 100)).subtract(held.debt()).max(BigDecimal.ZERO));
            gap.put("gold", after.multiply(BigDecimal.valueOf(target.gold() / 100)).subtract(held.gold()).max(BigDecimal.ZERO));
            BigDecimal gaps = gap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, BigDecimal> split = new LinkedHashMap<>();
            for (var e : gap.entrySet()) {
                double t = switch (e.getKey()) { case "equity" -> target.equity(); case "debt" -> target.debt(); default -> target.gold(); };
                BigDecimal share = gaps.signum() > 0
                    ? left.multiply(e.getValue()).divide(gaps, 0, java.math.RoundingMode.HALF_UP)
                    : left.multiply(BigDecimal.valueOf(t / 100)).setScale(0, java.math.RoundingMode.HALF_UP);
                split.put(e.getKey(), share);
            }
            split.put("gold", left.subtract(split.get("equity")).subtract(split.get("debt")).max(BigDecimal.ZERO));

            BigDecimal equity = split.get("equity");
            if (equity.signum() > 0) {
                double[] caps = capSplit(risk);
                Part[] parts = { Part.EQUITY_CORE, Part.EQUITY_FLEXI, Part.EQUITY_MIDSMALL };
                String[] why = {
                    "The steady core of the equity share: the 50–100 largest companies, at the lowest cost through an index fund.",
                    "Lets the manager move between large, mid and small companies as valuations change.",
                    "Higher growth and deeper falls — worth holding only for 7 years or more."
                };
                BigDecimal given = BigDecimal.ZERO;
                int last = caps[2] > 0 ? 2 : 1;
                for (int i = 0; i <= last; i++) {
                    // The last part takes the rounding remainder, so the steps add up to the amount.
                    BigDecimal a = i == last ? equity.subtract(given)
                        : equity.multiply(BigDecimal.valueOf(caps[i])).setScale(0, java.math.RoundingMode.HALF_UP);
                    given = given.add(a);
                    if (a.signum() > 0) steps.add(step(parts[i], a, amount, why[i], own.getOrDefault(parts[i], List.of())));
                }
                if (equity.compareTo(STAGGER_ABOVE) > 0) {
                    notes.add(String.format("₹%,.0f is going into equity. Rather than investing it on one day, park it in a liquid fund and move it "
                        + "in over 6–12 months (an STP), so one bad week does not set the price for all of it.", equity));
                }
            }
            if (split.get("debt").signum() > 0) {
                steps.add(step(Part.DEBT, split.get("debt"), amount, horizonYears < 3
                    ? "Money needed within three years should not depend on the stock market."
                    : "The cushion that keeps falls in equity bearable, and the part you rebalance from.", own.getOrDefault(Part.DEBT, List.of())));
            }
            if (split.get("gold").signum() > 0) {
                steps.add(step(Part.GOLD, split.get("gold"), amount, "A small share that has tended to rise when equity falls.", own.getOrDefault(Part.GOLD, List.of())));
            }
            notes.add(String.format("Target mix for %d year%s, %s risk: %.0f%% equity, %.0f%% debt, %.0f%% gold. "
                + "Your investments today are %.0f%% / %.0f%% / %.0f%%, so new money goes first to what is below target.",
                horizonYears, horizonYears == 1 ? "" : "s", risk.name().toLowerCase(), target.equity(), target.debt(), target.gold(),
                current.equity(), current.debt(), current.gold()));
        }
        return new Plan(amount, horizonYears, risk, current, target, monthlySpend, steps, notes, DISCLAIMER);
    }

    /** Equity / debt / gold by horizon and risk. Short money stays out of equity whatever the risk. */
    static Mix targetMix(int horizonYears, Risk risk) {
        if (horizonYears < 3) return switch (risk) {
            case CONSERVATIVE -> new Mix(0, 95, 5);
            case MODERATE -> new Mix(10, 85, 5);
            case AGGRESSIVE -> new Mix(20, 75, 5);
        };
        if (horizonYears < 5) return switch (risk) {
            case CONSERVATIVE -> new Mix(30, 60, 10);
            case MODERATE -> new Mix(50, 40, 10);
            case AGGRESSIVE -> new Mix(65, 25, 10);
        };
        return switch (risk) {
            case CONSERVATIVE -> new Mix(45, 45, 10);
            case MODERATE -> new Mix(65, 25, 10);
            case AGGRESSIVE -> new Mix(80, 10, 10);
        };
    }

    /** Share of the equity part for large/index, flexi/multi, and mid/small cap. */
    static double[] capSplit(Risk risk) {
        return switch (risk) {
            case CONSERVATIVE -> new double[] { 0.7, 0.3, 0 };
            case MODERATE -> new double[] { 0.5, 0.35, 0.15 };
            case AGGRESSIVE -> new double[] { 0.35, 0.35, 0.3 };
        };
    }

    private static Step step(Part p, BigDecimal amount, BigDecimal of, String why, List<FundPick> funds) {
        return new Step(p, p.label, amount, pct(amount, of), p.where, why, funds, List.of(), null);
    }

    /** Direct stocks and equity funds; FD, RD, EPF and debt funds; gold funds. Hybrid funds split 65/35. Bank cash is the emergency fund, not an investment. */
    private Holdings holdingsByClass(PortfolioContext ctx, List<Holding> holdings) {
        BigDecimal equity = nz(ctx.getStocksValue());
        BigDecimal debt = nz(ctx.getFdValue()).add(nz(ctx.getRdValue())).add(nz(ctx.getEpfValue()));
        BigDecimal gold = BigDecimal.ZERO;
        for (Holding h : holdings) {
            if (!PortfolioContextService.isMf(h)) continue;
            BigDecimal v = nz(h.getCurrentValue());
            switch (bucket(h)) {
                case DEBT -> debt = debt.add(v);
                case LIQUID -> { } // counted in the emergency fund
                case GOLD_SILVER -> gold = gold.add(v);
                case HYBRID -> { equity = equity.add(v.multiply(new BigDecimal("0.65"))); debt = debt.add(v.multiply(new BigDecimal("0.35"))); }
                default -> equity = equity.add(v);
            }
        }
        return new Holdings(equity, debt, gold);
    }

    /** The user's own funds for each part, strongest 3-year record first. */
    private Map<Part, List<FundPick>> ownFunds(List<Holding> holdings) {
        Map<Part, List<FundPick>> out = new EnumMap<>(Part.class);
        for (Holding h : holdings) {
            if (!PortfolioContextService.isMf(h)) continue;
            Part part = switch (bucket(h)) {
                case LARGE_CAP, INDEX -> Part.EQUITY_CORE;
                case FLEXI_CAP, MULTI_CAP, LARGE_AND_MID_CAP, ELSS -> Part.EQUITY_FLEXI;
                case MID_CAP, SMALL_CAP -> Part.EQUITY_MIDSMALL;
                case DEBT -> Part.DEBT;
                case LIQUID -> Part.EMERGENCY;
                case GOLD_SILVER -> Part.GOLD;
                default -> null; // hybrid, sectoral, international: not a building block here
            };
            if (part == null) continue;
            Double r3 = null, r1 = null;
            if (peers != null && h.getAmfiSchemeCode() != null) {
                try {
                    var m = peers.compare(h.getAmfiSchemeCode()).map(MfPeerComparisonService.Comparison::fund).orElse(null);
                    if (m != null) {
                        r3 = m.getReturn3YCagr() != null ? m.getReturn3YCagr().doubleValue() : null;
                        r1 = m.getReturn1Y() != null ? m.getReturn1Y().doubleValue() : null;
                    }
                } catch (Exception e) {
                    log.debug("No record for {}: {}", h.getAmfiSchemeCode(), e.getMessage());
                }
            }
            out.computeIfAbsent(part, k -> new ArrayList<>()).add(new FundPick(h.getName(), h.getAmfiSchemeCode(), r3, r1, nz(h.getCurrentValue())));
        }
        Comparator<FundPick> strongest = Comparator.comparing((FundPick f) -> f.return3Y() != null ? f.return3Y() : f.return1Y() != null ? f.return1Y() - 100 : -1000.0).reversed();
        out.values().forEach(l -> l.sort(strongest));
        return out;
    }

    private MfCategoryBucket bucket(Holding h) {
        AmfiNavResult r = h.getAmfiSchemeCode() != null ? amfi.findByCode(h.getAmfiSchemeCode()) : null;
        return MfCategoryBucket.from(r != null ? r.getCategory() : h.getName());
    }

    /** Average of the last three complete months, investments and own-account transfers excluded. */
    private BigDecimal monthlySpend(Long userId) {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        BigDecimal total = BigDecimal.ZERO;
        int months = 0;
        for (int i = 1; i <= 3; i++) {
            LocalDate from = first.minusMonths(i);
            BigDecimal m = BigDecimal.ZERO;
            for (Object[] row : expenses.sumByCategory(userId, from, from.withDayOfMonth(from.lengthOfMonth()))) {
                if (row[1] != null) m = m.add((BigDecimal) row[1]);
            }
            if (m.signum() > 0) { total = total.add(m); months++; }
        }
        return months == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(months), 0, java.math.RoundingMode.HALF_UP);
    }

    private static double pct(BigDecimal part, BigDecimal of) {
        return of.signum() == 0 ? 0 : Math.round(part.doubleValue() / of.doubleValue() * 1000) / 10.0;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
