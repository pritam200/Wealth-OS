package com.marketai.admin.service;

import com.marketai.admin.domain.AdminRequestLog;
import com.marketai.admin.repo.AdminRequestLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class RequestLogService {

    private final AdminRequestLogRepository repo;

    public RequestLogService(AdminRequestLogRepository repo) { this.repo = repo; }

    /** Monitoring must never break the request it describes. */
    public void save(AdminRequestLog row) {
        try { repo.save(row); } catch (Exception e) { log.warn("Could not write admin request log: {}", e.getClass().getSimpleName()); }
    }

    public Page<AdminRequestLog> search(Instant from, Instant to, String ip, String email, String path, Integer status,
                                        String decision, int page, int size) {
        Specification<AdminRequestLog> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            if (ip != null && !ip.isBlank()) p.add(cb.equal(root.get("sourceIp"), ip.trim()));
            if (email != null && !email.isBlank()) p.add(cb.equal(cb.lower(root.get("actorEmail")), email.trim().toLowerCase(Locale.ROOT)));
            if (path != null && !path.isBlank()) p.add(cb.like(root.get("path"), escape(path.trim()) + "%", '\\'));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (decision != null && !decision.isBlank()) p.add(cb.equal(root.get("decision"), decision.trim().toUpperCase(Locale.ROOT)));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return repo.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200), Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
