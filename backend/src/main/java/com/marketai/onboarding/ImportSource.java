package com.marketai.onboarding;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One place a user keeps money (a broker, a fund platform, a bank's FD/RD, a card) and how far
 * its data has been brought in. {@code syncedThrough} is the watermark: the latest date the
 * imported data is known to cover. The next import only needs data after it.
 */
@Entity
@Table(name = "import_sources", indexes = @Index(name = "idx_import_source_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ImportSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private SourceKind kind;

    /** What the user calls it: "Zerodha", "HDFC Bank", "ICICI Amazon Pay card". */
    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "synced_through")
    private LocalDate syncedThrough;

    @Column(name = "last_import_at")
    private LocalDateTime lastImportAt;

    @Column(name = "last_import_note", length = 200)
    private String lastImportNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
