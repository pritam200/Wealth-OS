package com.marketai.research.model;

import java.time.LocalDateTime;

/**
 * One external item research drew on: a filing, a corporate action, a news article or a web page.
 *
 * @param id          "E3" — stable within one research context
 * @param kind        FILING | CORPORATE_ACTION | EVENT | RESULTS_FILING | NEWS | WEB
 * @param tier        PRIMARY (exchange/company filing) > OFFICIAL (company release) > RELIABLE
 *                    (established financial press) > NEWS > UNVERIFIED
 * @param relevance   HIGH | MEDIUM | LOW — deterministic: names the company, recency, tier
 * @param detail      optional short text (filing subject, grounded web statement)
 */
public record Evidence(String id, String kind, String tier, String source, String title, LocalDateTime publishedAt,
                       String url, LocalDateTime retrievedAt, String relevance, String detail) {

    public Evidence withId(String newId) {
        return new Evidence(newId, kind, tier, source, title, publishedAt, url, retrievedAt, relevance, detail);
    }
}
