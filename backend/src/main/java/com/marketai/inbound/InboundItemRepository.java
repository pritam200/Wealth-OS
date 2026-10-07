package com.marketai.inbound;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InboundItemRepository extends JpaRepository<InboundItem, Long> {
    List<InboundItem> findTop20ByUserIdOrderByReceivedAtDesc(Long userId);
    Optional<InboundItem> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndSha256AndStatus(Long userId, String sha256, InboundItem.Status status);

    @Modifying
    @Query("update InboundItem i set i.pdfBytes = null, i.status = com.marketai.inbound.InboundItem.Status.FAILED, i.note = 'Expired: no password was given in time' "
        + "where i.status = com.marketai.inbound.InboundItem.Status.NEEDS_PASSWORD and i.receivedAt < :before")
    int expireWaiting(LocalDateTime before);
}
