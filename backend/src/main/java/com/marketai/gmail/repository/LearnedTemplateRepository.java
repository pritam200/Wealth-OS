package com.marketai.gmail.repository;

import com.marketai.gmail.entity.LearnedTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearnedTemplateRepository extends JpaRepository<LearnedTemplate, Long> {
    List<LearnedTemplate> findByUserIdOrderByTimesUsedDescCreatedAtDesc(Long userId);
    List<LearnedTemplate> findByUserIdAndSenderDomainAndSubjectShapeOrderByTimesUsedDesc(Long userId, String senderDomain, String subjectShape);
    boolean existsByUserIdAndPatternHash(Long userId, String patternHash);
    boolean existsByIdAndUserId(Long id, Long userId);
}
