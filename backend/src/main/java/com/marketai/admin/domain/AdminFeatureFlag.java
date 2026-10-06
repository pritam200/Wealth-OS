package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "admin_feature_flag")
@Getter @Setter @NoArgsConstructor
public class AdminFeatureFlag {
    @Id @Column(length = 80) private String name;
    private boolean enabled;
    @Column(length = 150) private String updatedBy;
    private Instant updatedAt;
}
