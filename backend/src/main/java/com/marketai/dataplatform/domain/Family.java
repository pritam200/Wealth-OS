package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A family: members share data only through explicit memberships. */
@Entity
@Table(name = "families")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Family {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
