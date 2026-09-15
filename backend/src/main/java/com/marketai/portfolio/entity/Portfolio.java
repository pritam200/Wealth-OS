package com.marketai.portfolio.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.marketai.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "portfolios", indexes = @Index(name = "idx_portfolio_user", columnList = "user_id"))
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
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @JsonIgnore
    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Holding> holdings = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
