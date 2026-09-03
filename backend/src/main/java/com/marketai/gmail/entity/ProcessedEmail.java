package com.marketai.gmail.entity;

import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_emails",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "gmail_message_id"}),
       indexes = {
           @Index(name = "idx_pe_user_status", columnList = "user_id, status"),
           @Index(name = "idx_pe_user_processed", columnList = "user_id, processedAt")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProcessedEmail {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    private LocalDateTime processedAt;
    private String type;          // TRADE_BUY / TRADE_SELL / FD_OPEN / RD_OPEN / MF_SIP / UNKNOWN
    private String status;        // IMPORTED / SKIPPED / FAILED

    // Simple class name of the EmailParser that matched (e.g. "HdfcBankParser"), or null
    // if no parser matched — lets sync issues be debugged per-bank/source, not just by type.
    private String matchedParser;

    // The email's From address — lets the sync-history UI offer a one-click "Exclude this
    // sender" action (e.g. for a shared inbox that also receives a parent's account emails).
    @Column(length = 320)
    private String sender;

    @Column(length = 1000)
    private String resultSummary;

    @PrePersist
    void onCreate() { if (processedAt == null) processedAt = LocalDateTime.now(); }
}
