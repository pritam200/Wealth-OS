package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "other_assets")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OtherAsset {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String name;

    // ppf | epf | nps | gold | realestate | savings | insurance | usstocks | other
    @Column(nullable = false, length = 50)
    private String category;

    // `value` is a reserved word in H2 (and several other dialects), so the identifier is
    // quoted. Backticks are Hibernate's portable quoting syntax — it rewrites them to whatever
    // the active dialect uses. The column name itself is unchanged, so no migration is needed.
    @Column(name = "`value`", nullable = false, precision = 15, scale = 2)
    private BigDecimal value;

    @Column(length = 500)
    private String note;

    @Column(name = "as_of")
    private LocalDate asOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
