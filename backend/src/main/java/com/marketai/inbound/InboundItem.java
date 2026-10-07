package com.marketai.inbound;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One attachment that arrived by forwarding, and what became of it. */
@Entity
@Table(name = "inbound_items", indexes = @Index(name = "idx_inbound_item_user", columnList = "user_id, received_at"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InboundItem {
    public enum Status { IMPORTED, NEEDS_PASSWORD, FAILED, IGNORED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "received_at", nullable = false) private LocalDateTime receivedAt;
    @Column(name = "from_address", length = 200) private String fromAddress;
    @Column(length = 200) private String subject;
    @Column(length = 200) private String filename;
    @Column(length = 64) private String sha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(length = 500) private String note;
    /** Kept only while waiting for a password (the PDF is still encrypted); cleared once imported or dismissed. */
    @Basic(fetch = FetchType.LAZY) @Column(name = "pdf_bytes") private byte[] pdfBytes;
}
