package com.marketai.ai.entity;

import com.marketai.auth.entity.User;
import javax.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_history", indexes = {
        @Index(name = "idx_ai_user", columnList = "user_id"),
        @Index(name = "idx_ai_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QueryType queryType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String response;

    @Column(length = 50)
    private String relatedSymbol;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum QueryType {
        STOCK_ANALYSIS,
        MARKET_SUMMARY,
        PORTFOLIO_REVIEW,
        FORECAST,
        CHAT
    }
}
