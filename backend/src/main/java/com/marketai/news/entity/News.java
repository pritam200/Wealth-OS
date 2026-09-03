package com.marketai.news.entity;

import javax.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "news", indexes = {
        @Index(name = "idx_news_published", columnList = "published_at"),
        @Index(name = "idx_news_symbol", columnList = "related_symbol"),
        @Index(name = "idx_news_sentiment", columnList = "sentiment")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 2048)
    private String url;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String source;

    @Column(name = "related_symbol", length = 50)
    private String relatedSymbol;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    @Builder.Default
    private Sentiment sentiment = Sentiment.NEUTRAL;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime fetchedAt = LocalDateTime.now();

    public enum Sentiment {
        POSITIVE, NEGATIVE, NEUTRAL
    }
}
