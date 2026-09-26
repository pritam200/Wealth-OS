package com.marketai.ai.audit.repository;

import com.marketai.ai.audit.entity.AiAuditTrail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAuditTrailRepository extends JpaRepository<AiAuditTrail, Long> {
    List<AiAuditTrail> findByUserIdOrderByCreatedAtDesc(Long userId, PageRequest page);
    List<AiAuditTrail> findByUserIdAndTaskOrderByCreatedAtDesc(Long userId, String task, PageRequest page);
    long countByUserIdAndStatus(Long userId, String status);

    // referenceId is how a Gmail message id (or other source id) traces back to the AI calls
    // that reasoned over it — see EmailLLMParserService, which records EXTRACT_TASK rows keyed
    // by gmailMessageId, and Expense/Income/Rent/CardPayment.sourceEmailId, which stores the
    // same id on the record the extraction produced.
    List<AiAuditTrail> findByUserIdAndReferenceIdOrderByCreatedAtDesc(Long userId, String referenceId);
}
