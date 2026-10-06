package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Deliberately not a JpaRepository: there is no update or delete to call. */
public interface AdminAuditRepository extends Repository<AdminAuditEvent, Long> {
    AdminAuditEvent save(AdminAuditEvent event);
    Optional<AdminAuditEvent> findTopByOrderByIdDesc();
    Page<AdminAuditEvent> findAllByOrderByIdDesc(Pageable pageable);
    Slice<AdminAuditEvent> findAllByOrderByIdAsc(Pageable pageable);
    long count();
}
