package com.marketai.research.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.research.entity.ResearchRecord;
import com.marketai.research.model.ResearchResult;
import com.marketai.research.repository.ResearchRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Stored research, keyed by exactly what produced it. Read by the orchestrator and by the
 * screens that only show research (analyst panel, recommendations, Today's Actions), so those
 * never trigger a model call themselves.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchCache {

    private final ResearchRecordRepository repo;
    private final ObjectMapper mapper;

    public record Key(String subjectType, String symbol, Long userId, String marketDate, String snapshotHash,
                      String promptVersion, String provider, String model) {}

    public Optional<ResearchResult> find(Key k) {
        return repo.findFirstBySubjectTypeAndSymbolAndUserScopeAndMarketDateAndSnapshotHashAndPromptVersionAndProviderAndModelOrderByCreatedAtDesc(
                k.subjectType(), k.symbol(), scope(k.userId()), date(k.marketDate()), k.snapshotHash(), k.promptVersion(), k.provider(), k.model())
                .flatMap(this::read);
    }

    /** The newest research for a subject, whatever produced it — callers label its age. */
    public Optional<ResearchResult> latest(String subjectType, String symbol, Long userId) {
        return repo.findFirstBySubjectTypeAndSymbolAndUserScopeOrderByCreatedAtDesc(subjectType, symbol, scope(userId)).flatMap(this::read);
    }

    /**
     * The newest research for a subject made for this session, whoever asked for it. For
     * screens with no user in scope; callers show only the code-generated final view from it.
     */
    public Optional<ResearchResult> currentForSymbol(String subjectType, String symbol, LocalDate marketDate) {
        if (marketDate == null) return Optional.empty();
        return repo.findFirstBySubjectTypeAndSymbolAndMarketDateOrderByCreatedAtDesc(subjectType, symbol, marketDate).flatMap(this::read);
    }

    public void save(ResearchResult r, String promptVersion) {
        ResearchRecord rec = new ResearchRecord();
        rec.setSubjectType(r.getSubjectType());
        rec.setSymbol(r.getSymbol());
        rec.setUserScope(scope(r.getUserScope()));
        rec.setMarketDate(date(r.getMarketDate()));
        rec.setSnapshotHash(r.getDataSnapshotHash());
        rec.setPromptVersion(promptVersion);
        rec.setProvider(r.getProvider());
        rec.setModel(r.getModel());
        rec.setStatus(r.getStatus());
        rec.setCreatedAt(r.getResearchTimestamp() != null ? r.getResearchTimestamp() : LocalDateTime.now());
        try {
            rec.setPayload(mapper.writeValueAsString(r));
            repo.save(rec);
        } catch (DataIntegrityViolationException e) {
            log.debug("Research for {} already stored by a concurrent run", r.getSymbol());
        } catch (Exception e) {
            log.warn("Could not store research for {}: {}", r.getSymbol(), e.getMessage());
        }
    }

    private Optional<ResearchResult> read(ResearchRecord rec) {
        try {
            return Optional.of(mapper.readValue(rec.getPayload(), ResearchResult.class));
        } catch (Exception e) {
            log.warn("Stored research {} could not be read: {}", rec.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    static long scope(Long userId) { return userId == null ? 0L : userId; }

    static LocalDate date(String s) {
        try { return s == null ? null : LocalDate.parse(s); } catch (Exception e) { return null; }
    }
}
