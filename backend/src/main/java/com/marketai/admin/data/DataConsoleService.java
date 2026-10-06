package com.marketai.admin.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.admin.data.TablePolicy.Col;
import com.marketai.admin.domain.AdminChangeRequest;
import com.marketai.admin.domain.AdminRole;
import com.marketai.admin.repo.AdminChangeRequestRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.service.AdminAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Policy-driven reads and edits of production data. No SQL text ever comes from a caller. */
@Service
public class DataConsoleService {

    private final TablePolicies policies;
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final AdminChangeRequestRepository changes;
    private final ObjectMapper mapper;

    public DataConsoleService(TablePolicies policies, JdbcTemplate jdbc, AdminAuditService audit,
                              AdminChangeRequestRepository changes, ObjectMapper mapper) {
        this.policies = policies; this.jdbc = jdbc; this.audit = audit; this.changes = changes; this.mapper = mapper;
    }

    public record Preview(Map<String, Object> before, Map<String, Object> after, List<String> sensitiveFields, boolean needsApproval) {}
    public record Page(List<Map<String, Object>> rows, long total, int page, int size) {}

    public List<Map<String, Object>> tables() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TablePolicy p : policies.all()) {
            out.add(Map.of("table", p.table(), "label", p.label(), "idColumn", p.idColumn(),
                "columns", p.columns().stream().map(c -> Map.of("name", c.name(), "type", c.type(), "editable", c.editable(),
                    "sensitive", c.sensitive(), "searchable", c.searchable())).toList()));
        }
        return out;
    }

    public Page search(String table, String q, int page, int size) {
        TablePolicy p = policy(table);
        int sz = Math.min(Math.max(1, size), 100), pg = Math.max(0, page);
        String cols = String.join(", ", p.columns().stream().map(Col::name).toList());
        List<Object> args = new ArrayList<>();
        String where = "";
        if (q != null && !q.isBlank()) {
            List<String> likes = new ArrayList<>();
            for (Col c : p.columns()) if (c.searchable() && c.type() == TablePolicy.Type.STRING) { likes.add("LOWER(" + c.name() + ") LIKE ? ESCAPE '\\'"); args.add("%" + escape(q.trim().toLowerCase()) + "%"); }
            if (q.trim().matches("\\d{1,18}")) { likes.add(p.idColumn() + " = ?"); args.add(Long.parseLong(q.trim())); }
            if (!likes.isEmpty()) where = " WHERE " + String.join(" OR ", likes);
        }
        long total = Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM " + p.table() + where, Long.class, args.toArray())).orElse(0L);
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(sz); pageArgs.add((long) pg * sz);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + cols + " FROM " + p.table() + where
            + " ORDER BY " + p.idColumn() + " DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return new Page(rows.stream().map(this::lower).toList(), total, pg, sz);
    }

    public Map<String, Object> get(String table, String id) {
        TablePolicy p = policy(table);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + String.join(", ", p.columns().stream().map(Col::name).toList())
            + " FROM " + p.table() + " WHERE " + p.idColumn() + " = ?", idValue(id));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such record.");
        return lower(rows.get(0));
    }

    public Preview preview(String table, String id, Map<String, Object> requested) {
        TablePolicy p = policy(table);
        Map<String, Object> current = get(table, id);
        Map<String, Object> clean = validate(p, requested);
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        List<String> sensitive = new ArrayList<>();
        clean.forEach((k, v) -> { before.put(k, current.get(k)); after.put(k, v); if (p.column(k).sensitive()) sensitive.add(k); });
        return new Preview(before, after, sensitive, !sensitive.isEmpty());
    }

    /** Applies ordinary fields now; sensitive ones become a change request for a second administrator. */
    @Transactional
    public Map<String, Object> update(AdminContext ctx, String table, String id, Map<String, Object> requested, String reason) {
        if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for data changes.");
        TablePolicy p = policy(table);
        Preview pv = preview(table, id, requested);
        Map<String, Object> clean = validate(p, requested);
        Map<String, Object> direct = new LinkedHashMap<>(), gated = new LinkedHashMap<>();
        clean.forEach((k, v) -> (p.column(k).sensitive() ? gated : direct).put(k, v));
        Map<String, Object> result = new LinkedHashMap<>();
        if (!direct.isEmpty()) {
            apply(p, id, direct);
            audit.record(ctx, "DATA_UPDATED", table, id, "UPDATE", pick(pv.before(), direct.keySet()), direct, reason);
            result.put("applied", direct.keySet());
        }
        if (!gated.isEmpty()) {
            AdminChangeRequest cr = new AdminChangeRequest();
            cr.setTableName(table); cr.setRecordId(id); cr.setReason(reason); cr.setRequestedBy(ctx.email()); cr.setRequestedAt(Instant.now());
            cr.setChangesJson(json(gated));
            cr = changes.save(cr);
            audit.record(ctx, "DATA_CHANGE_REQUESTED", table, id, "REQUEST", pick(pv.before(), gated.keySet()), gated, reason);
            result.put("pendingApproval", cr.getId());
        }
        return result;
    }

    public List<AdminChangeRequest> changeRequests() { return changes.findTop100ByOrderByIdDesc(); }

    @Transactional
    public AdminChangeRequest decide(AdminContext ctx, Long id, boolean approve, String reason) {
        AdminChangeRequest cr = changes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such request."));
        if (cr.getStatus() != AdminChangeRequest.Status.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already decided.");
        if (ctx.role() != AdminRole.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can decide.");
        if (cr.getRequestedBy().equalsIgnoreCase(ctx.email()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A second administrator must decide: you cannot approve your own request.");
        cr.setDecidedBy(ctx.email()); cr.setDecidedAt(Instant.now());
        if (!approve) {
            cr.setStatus(AdminChangeRequest.Status.REJECTED); cr.setOutcome("Rejected");
            audit.record(ctx, "DATA_CHANGE_REJECTED", cr.getTableName(), cr.getRecordId(), "REJECT", null, Map.of("requestId", id), reason);
            return changes.save(cr);
        }
        TablePolicy p = policy(cr.getTableName());
        Map<String, Object> clean = validate(p, parse(cr.getChangesJson()));
        Map<String, Object> before = pick(get(cr.getTableName(), cr.getRecordId()), clean.keySet());
        apply(p, cr.getRecordId(), clean);
        if ("users".equals(p.table()) && Boolean.FALSE.equals(clean.get("enabled")))   // a disabled customer must lose live sessions too
            jdbc.update("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?", idValue(cr.getRecordId()));
        cr.setStatus(AdminChangeRequest.Status.APPROVED); cr.setOutcome("Applied");
        audit.record(ctx, "DATA_CHANGE_APPROVED", cr.getTableName(), cr.getRecordId(), "UPDATE", before, clean,
            "approved request " + id + " by " + cr.getRequestedBy() + (reason == null ? "" : ": " + reason));
        return changes.save(cr);
    }

    // ---- internals ----

    private void apply(TablePolicy p, String id, Map<String, Object> values) {
        List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
        values.forEach((k, v) -> { sets.add(k + " = ?"); args.add(v); });
        if (p.touchColumn() != null) { sets.add(p.touchColumn() + " = ?"); args.add(Timestamp.from(Instant.now())); }
        args.add(idValue(id));
        int n = jdbc.update("UPDATE " + p.table() + " SET " + String.join(", ", sets) + " WHERE " + p.idColumn() + " = ?", args.toArray());
        if (n != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such record.");
    }

    private Map<String, Object> validate(TablePolicy p, Map<String, Object> requested) {
        if (requested == null || requested.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change.");
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : requested.entrySet()) {
            Col c = p.column(e.getKey());
            if (c == null || !c.editable()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The field '" + clip(e.getKey()) + "' cannot be edited here.");
            Object v = e.getValue();
            switch (c.type()) {
                case BOOLEAN -> { if (!(v instanceof Boolean)) throw bad(c, "true or false"); }
                case STRING -> {
                    if (!(v instanceof String s)) throw bad(c, "text");
                    if (s.length() > c.maxLength()) throw bad(c, "at most " + c.maxLength() + " characters");
                    if (s.matches(".*\\p{Cntrl}.*")) throw bad(c, "text without control characters");
                    if (c.name().equals("name") && s.isBlank()) throw bad(c, "non-empty text");
                }
                default -> throw bad(c, "a supported type");
            }
            out.put(c.name(), v);
        }
        return out;
    }

    private ResponseStatusException bad(Col c, String expected) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + c.name() + "' must be " + expected + "."); }
    private TablePolicy policy(String table) {
        TablePolicy p = policies.get(table);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That table is not available in the console.");
        return p;
    }
    private static Object idValue(String id) {
        if (id == null || !id.matches("\\d{1,18}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id.");
        return Long.parseLong(id);
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static String clip(String s) { return s.length() > 40 ? s.substring(0, 40) : s.replaceAll("\\p{Cntrl}", "?"); }
    private Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v instanceof Timestamp t ? t.toInstant().toString() : v));
        return m;
    }
    private static Map<String, Object> pick(Map<String, Object> m, Set<String> keys) {
        Map<String, Object> o = new LinkedHashMap<>(); keys.forEach(k -> o.put(k, m.get(k))); return o;
    }
    private String json(Object o) { try { return mapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Map<String, Object> parse(String s) { try { return mapper.readValue(s, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); } }
}
