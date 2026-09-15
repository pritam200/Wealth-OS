package com.marketai.ai.entity;

import com.marketai.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_history", indexes = {
        @Index(name = "idx_ai_user", columnList = "user_id"),
        @Index(name = "idx_ai_created", columnList = "created_at")
})
// Lombok's @Data generates equals, hashCode and toString over *every* field, including JPA
// relations. On an entity with a bidirectional mapping that recurses forever:
// Holding.hashCode() reads its portfolio, Portfolio.hashCode() reads its holdings list, which
// reads this holding again — a StackOverflowError, reproduced and confirmed before this fix.
// The same recursion applies to toString(), so simply logging an entity crashed the thread.
//
// On lazy relations it is also a LazyInitializationException (or a silent N+1) as soon as
// equals/hashCode is called outside a session.
//
// Identity is therefore the primary key alone, which is what JPA semantics actually mean by
// "the same row", and relations are excluded from toString.
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

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
