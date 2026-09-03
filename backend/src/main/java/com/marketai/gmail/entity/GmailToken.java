package com.marketai.gmail.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "gmail_tokens")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GmailToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @Column(length = 2000)
    private String accessToken;

    @Column(length = 2000)
    private String refreshToken;

    private LocalDateTime expiresAt;
    private String connectedEmail;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncAt;
    private Integer importedCount;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); importedCount = 0; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
