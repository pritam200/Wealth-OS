package com.marketai.news.service;

import com.marketai.news.entity.News;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dated headlines for a company or for the market: Google News RSS plus the articles the news
 * job has stored. The one source of headlines for the analyst's sentiment and the research
 * engine, so both read the same news.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyNewsService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NewsService newsService;

    /** What was tried and whether it answered — recorded with research so a gap is visible. */
    public record Fetch(List<NewsSentimentAnalyzer.Headline> headlines, List<String> sourcesOk, List<String> sourcesFailed) {}

    public Fetch forCompany(String base, String name) {
        List<NewsSentimentAnalyzer.Headline> out = new ArrayList<>();
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        String query = (name != null && name.length() > 2 ? name : base) + " stock NSE";
        googleNews(query, out, ok, failed);
        try {
            for (News n : newsService.getNewsBySymbol(base)) {
                if (n.getTitle() != null) out.add(new NewsSentimentAnalyzer.Headline(n.getTitle(), n.getUrl(),
                        n.getSource() != null ? n.getSource() : "News", n.getPublishedAt()));
            }
            ok.add("Stored news");
        } catch (Exception e) {
            log.debug("Stored news unavailable for {}: {}", base, e.getMessage());
            failed.add("Stored news");
        }
        return new Fetch(out, ok, failed);
    }

    /** Broad-market and macro headlines (Indian indices, RBI, crude, rupee, global markets). */
    public Fetch forMarket() {
        List<NewsSentimentAnalyzer.Headline> out = new ArrayList<>();
        List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
        googleNews("Nifty Sensex market today", out, ok, failed);
        googleNews("RBI inflation rupee crude India economy", out, ok, failed);
        try {
            for (News n : newsService.getLatestNews(0, 30).getContent()) {
                if (n.getTitle() != null) out.add(new NewsSentimentAnalyzer.Headline(n.getTitle(), n.getUrl(),
                        n.getSource() != null ? n.getSource() : "News", n.getPublishedAt()));
            }
            ok.add("Stored news");
        } catch (Exception e) {
            failed.add("Stored news");
        }
        return new Fetch(out, ok, failed);
    }

    private void googleNews(String query, List<NewsSentimentAnalyzer.Headline> out, List<String> ok, List<String> failed) {
        String label = "Google News (\"" + query + "\")";
        try {
            String url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&hl=en-IN&gl=IN&ceid=IN:en";
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            out.addAll(parseRss(sb.toString(), 20));
            ok.add(label);
        } catch (Exception e) {
            log.debug("news fetch failed for {}: {}", query, e.getMessage());
            failed.add(label);
        }
    }

    static List<NewsSentimentAnalyzer.Headline> parseRss(String xml, int max) {
        List<NewsSentimentAnalyzer.Headline> out = new ArrayList<>();
        Matcher m = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(xml);
        while (m.find() && out.size() < max) {
            String item = m.group(1);
            String title = tag(item, "title"), link = tag(item, "link"), date = tag(item, "pubDate"), src = tag(item, "source");
            if (title != null) out.add(new NewsSentimentAnalyzer.Headline(clean(title), link,
                    src != null ? clean(src) : "Google News", parseRfc1123(date)));
        }
        return out;
    }

    /** RSS pubDate → IST local time; null (and the headline excluded as undated) when unparseable. */
    public static LocalDateTime parseRfc1123(String d) {
        if (d == null) return null;
        try {
            return ZonedDateTime.parse(d.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(IST).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static String tag(String xml, String t) {
        Matcher m = Pattern.compile("<" + t + "[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + t + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String clean(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ").trim();
    }
}
