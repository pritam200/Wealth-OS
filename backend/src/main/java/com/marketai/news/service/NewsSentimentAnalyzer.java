package com.marketai.news.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headline sentiment for one company, reported as NEWS_SENTIMENT — kept apart from technical
 * signals and market trend, and never blended into a rating.
 *
 * Only articles that are dated within {@value #WINDOW_DAYS} days, name the company, and are not
 * duplicates of one another count. Terms match whole words only (the old substring match read
 * "ban" in every "Bank" headline and "rise" in "Enterprises"). Each counted headline is
 * POSITIVE, NEGATIVE or NEUTRAL by which lexicon it hits more; the score is
 * 100 × (positive − negative) ÷ all counted headlines, and is withheld below
 * {@value #MIN_ARTICLES} headlines. A keyword lexicon is a weak instrument, so confidence is
 * never above MEDIUM.
 */
public final class NewsSentimentAnalyzer {

    private NewsSentimentAnalyzer() {}

    public static final int WINDOW_DAYS = 14;
    public static final int MIN_ARTICLES = 3;

    private static final List<String> POSITIVE = List.of("surge", "surges", "jump", "jumps", "gain", "gains", "profit rises", "profit jumps",
            "beat", "beats", "record high", "upgrade", "upgrades", "upgraded", "bullish", "rally", "rallies", "wins", "bags", "order win",
            "approval", "approves", "soar", "soars", "rises", "outperform", "outperforms", "expansion", "acquires", "dividend", "buyback");
    private static final List<String> NEGATIVE = List.of("fall", "falls", "drop", "drops", "loss", "losses", "decline", "declines",
            "downgrade", "downgrades", "downgraded", "bearish", "probe", "fraud", "banned", "penalty", "fined", "weak", "miss", "misses",
            "crash", "slump", "slumps", "resigns", "resignation", "default", "defaults", "lawsuit", "scam", "plunge", "plunges", "raid");
    private static final Map<String, List<String>> EVENTS = new LinkedHashMap<>();
    static {
        EVENTS.put("RESULTS", List.of("results", "q1", "q2", "q3", "q4", "quarterly", "earnings", "profit", "revenue"));
        EVENTS.put("ORDER_OR_CONTRACT", List.of("order", "contract", "bags", "deal"));
        EVENTS.put("REGULATORY", List.of("sebi", "rbi", "probe", "penalty", "fined", "banned", "raid", "notice"));
        EVENTS.put("RATING_CHANGE", List.of("upgrade", "upgrades", "upgraded", "downgrade", "downgrades", "downgraded", "target price"));
        EVENTS.put("MANAGEMENT", List.of("ceo", "md", "chairman", "resigns", "appoints", "resignation"));
        EVENTS.put("CORPORATE_ACTION", List.of("dividend", "buyback", "split", "bonus", "rights issue", "merger", "acquires", "acquisition"));
    }
    private static final Pattern POS = wordPattern(POSITIVE), NEG = wordPattern(NEGATIVE);

    public record Headline(String title, String url, String source, LocalDateTime publishedAt) {}

    public record Classified(Headline headline, String sentiment, List<String> matched, String event, long ageDays) {}

    public record Result(String status, Integer score, String label, String confidence, int positive, int negative, int neutral,
                         int considered, int excludedOld, int excludedUndated, int excludedDuplicate, int excludedUnrelated,
                         List<Classified> counted, String method) {}

    public static final String METHOD = "Headlines from the last 14 days that name the company, de-duplicated; whole-word keyword lexicon; "
            + "score = 100 × (positive − negative) ÷ counted headlines; withheld below 3 headlines. Source and date shown per headline.";

    public static Result analyse(List<Headline> in, Collection<String> companyTerms, LocalDateTime now) {
        List<Pattern> names = companyTerms.stream().filter(t -> t != null && t.length() >= 3)
                .map(t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b", Pattern.CASE_INSENSITIVE)).toList();
        Set<String> seen = new HashSet<>();
        int old = 0, undated = 0, dup = 0, unrelated = 0, pos = 0, neg = 0, neu = 0;
        List<Classified> counted = new ArrayList<>();
        for (Headline h : in) {
            if (h.title() == null || h.title().isBlank()) continue;
            if (h.publishedAt() == null) { undated++; continue; }
            long age = ChronoUnit.DAYS.between(h.publishedAt(), now);
            if (age > WINDOW_DAYS) { old++; continue; }
            String title = stripSource(h.title());
            if (!seen.add(normalise(title))) { dup++; continue; }
            if (!names.isEmpty() && names.stream().noneMatch(p -> p.matcher(title).find())) { unrelated++; continue; }
            List<String> mp = matches(POS, title), mn = matches(NEG, title);
            String s = mp.size() > mn.size() ? "POSITIVE" : mn.size() > mp.size() ? "NEGATIVE" : "NEUTRAL";
            switch (s) { case "POSITIVE" -> pos++; case "NEGATIVE" -> neg++; default -> neu++; }
            List<String> all = new ArrayList<>(mp);
            all.addAll(mn);
            counted.add(new Classified(new Headline(title, h.url(), h.source(), h.publishedAt()), s, all, event(title), Math.max(0, age)));
        }
        int n = counted.size();
        if (n < MIN_ARTICLES) {
            return new Result("INSUFFICIENT", null, null, null, pos, neg, neu, n, old, undated, dup, unrelated, counted, METHOD);
        }
        int score = (int) Math.round(100.0 * (pos - neg) / n);
        String label = score >= 25 ? "POSITIVE" : score <= -25 ? "NEGATIVE" : pos > 0 && neg > 0 ? "MIXED" : "NEUTRAL";
        return new Result("OK", score, label, n >= 10 ? "MEDIUM" : "LOW", pos, neg, neu, n, old, undated, dup, unrelated, counted, METHOD);
    }

    /** Google News appends " - Publisher" to titles; drop it so the same story from two feeds de-duplicates. */
    static String stripSource(String t) {
        int i = t.lastIndexOf(" - ");
        return i > 20 ? t.substring(0, i).trim() : t.trim();
    }

    static String normalise(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String event(String title) {
        for (Map.Entry<String, List<String>> e : EVENTS.entrySet()) {
            if (!matches(wordPattern(e.getValue()), title).isEmpty()) return e.getKey();
        }
        return "GENERAL";
    }

    private static Pattern wordPattern(List<String> terms) {
        return Pattern.compile("\\b(" + String.join("|", terms.stream().map(Pattern::quote).toList()) + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    private static List<String> matches(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return out;
    }
}
