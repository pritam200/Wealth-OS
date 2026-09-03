package com.marketai.news.service;

import com.marketai.news.entity.News;
import com.marketai.news.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsService {

    private final NewsRepository newsRepository;

    // Free RSS feeds — no API key required
    private static final String[] RSS_FEEDS = {
        "https://news.google.com/rss/search?q=NSE+Nifty+Sensex+indian+stock+market&hl=en-IN&gl=IN&ceid=IN:en",
        "https://news.google.com/rss/search?q=NSE+Nifty+Sensex+indian+stock+market+when:4d&hl=en-IN&gl=IN&ceid=IN:en",
        "https://economictimes.indiatimes.com/markets/rss.cms",
        "https://www.moneycontrol.com/rss/MCtopnews.xml",
        "https://www.moneycontrol.com/rss/marketreports.xml",
        "https://www.livemint.com/rss/markets",
        "https://www.business-standard.com/rss/markets-106.rss",
    };

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    };

    public Page<News> getLatestNews(int page, int size) {
        Page<News> result = newsRepository.findByOrderByPublishedAtDesc(PageRequest.of(page, size));
        if (result.isEmpty()) {
            fetchLatestMarketNews();
            result = newsRepository.findByOrderByPublishedAtDesc(PageRequest.of(page, size));
        }
        return result;
    }

    public List<News> getNewsBySymbol(String symbol) {
        return newsRepository.findByRelatedSymbolOrderByPublishedAtDesc(symbol);
    }

    @Scheduled(fixedRateString = "${app.news.refresh-rate-ms:1800000}")
    public void fetchLatestMarketNews() {
        int total = 0;
        for (String feedUrl : RSS_FEEDS) {
            try {
                total += fetchRssFeed(feedUrl);
            } catch (Exception e) {
                log.debug("RSS feed failed {}: {}", feedUrl, e.getMessage());
            }
        }
        if (total > 0) log.info("Fetched {} new articles from RSS feeds", total);
    }

    private int fetchRssFeed(String feedUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(feedUrl).openConnection();
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        int saved = 0;
        try (InputStream is = conn.getInputStream()) {
            // Read bytes and strip any HTML DOCTYPE / BOM before passing to XML parser
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            String raw = baos.toString("UTF-8");
            // Remove DOCTYPE declarations that break the XML parser
            raw = raw.replaceFirst("(?is)<!DOCTYPE[^>]*>", "");
            // Remove BOM
            if (raw.startsWith("﻿")) raw = raw.substring(1);

            Document doc;
            try {
                javax.xml.parsers.DocumentBuilder builder =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder();
                // Suppress external entity resolution warnings
                builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
                doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(raw)));
            } catch (Exception parseEx) {
                log.debug("XML parse failed for {}: {}", feedUrl, parseEx.getMessage());
                return 0;
            }
            doc.getDocumentElement().normalize();

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                try {
                    org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
                    String link  = text(item, "link");
                    String title = text(item, "title");
                    if (link.trim().isEmpty() || title.trim().isEmpty()) continue;

                    // Truncate URL to fit column
                    if (link.length() > 2000) link = link.substring(0, 2000);
                    if (newsRepository.existsByUrl(link)) continue;

                    String description = text(item, "description");
                    String pubDate     = text(item, "pubDate");
                    String source      = text(item, "source");
                    if (source.trim().isEmpty()) source = extractDomain(feedUrl);

                    description = description.replaceAll("<[^>]+>", " ").trim();
                    if (description.length() > 800) description = description.substring(0, 797) + "...";

                    LocalDateTime publishedAt = parseDate(pubDate);

                    News news = News.builder()
                        .url(link)
                        .title(title.length() > 500 ? title.substring(0, 497) + "..." : title)
                        .description(description)
                        .source(source)
                        .publishedAt(publishedAt)
                        .sentiment(classifySentiment(title + " " + description))
                        .build();

                    saveOne(news);
                    saved++;
                } catch (Exception e) {
                    log.debug("Skipping article: {}", e.getMessage());
                }
            }
        }
        return saved;
    }

    @Transactional
    public void saveOne(News news) {
        newsRepository.save(news);
    }

    private String text(org.w3c.dom.Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        org.w3c.dom.Node n = nl.item(0);
        return n == null || n.getTextContent() == null ? "" : n.getTextContent().trim();
    }

    private String extractDomain(String url) {
        try {
            String host = new URL(url).getHost();
            return host.replace("www.", "").replace("news.", "");
        } catch (Exception e) {
            return "News";
        }
    }

    private LocalDateTime parseDate(String pubDate) {
        if (pubDate == null || pubDate.trim().isEmpty()) return LocalDateTime.now();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(pubDate.trim(), fmt).toLocalDateTime();
            } catch (DateTimeParseException ignored) {}
        }
        return LocalDateTime.now();
    }

    private News.Sentiment classifySentiment(String text) {
        String lower = text.toLowerCase();
        long pos = countKeywords(lower,
            "surge", "rally", "gains", "bullish", "record", "high", "growth",
            "profit", "strong", "buy", "upgrade", "outperform", "rise", "soar",
            "boost", "recovery", "jump", "optimism");
        long neg = countKeywords(lower,
            "fall", "drop", "bearish", "loss", "decline", "weak", "sell",
            "downgrade", "crash", "slump", "concern", "risk", "slide",
            "plunge", "tumble", "fear", "worry", "pressure");
        if (pos > neg) return News.Sentiment.POSITIVE;
        if (neg > pos) return News.Sentiment.NEGATIVE;
        return News.Sentiment.NEUTRAL;
    }

    private long countKeywords(String text, String... keywords) {
        long count = 0;
        for (String kw : keywords) if (text.contains(kw)) count++;
        return count;
    }
}
