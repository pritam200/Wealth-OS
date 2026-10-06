package com.marketai.admin.repo;

import com.marketai.admin.domain.EmailAllowEntry;
import com.marketai.admin.domain.EntryKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmailAllowRepository extends JpaRepository<EmailAllowEntry, Long> {
    List<EmailAllowEntry> findAllByOrderByIdAsc();

    @Query("select e from EmailAllowEntry e where e.kind = :kind and e.emailOrDomain = :value")
    List<EmailAllowEntry> findByKindAndValue(@Param("kind") EntryKind kind, @Param("value") String value);

    @Query("select count(e) > 0 from EmailAllowEntry e where e.kind = :kind and e.emailOrDomain = :value")
    boolean existsByKindAndValue(@Param("kind") EntryKind kind, @Param("value") String value);
}
