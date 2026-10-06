package com.marketai.auth.service;

import com.marketai.admin.security.AdminSettings;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * Permanently deletes a customer and everything they own, in one transaction. The tables are
 * discovered from the database itself rather than listed by hand, so a table added next year is
 * covered without anyone remembering to update this class: every table with a {@code user_id}
 * column is cleared, together with whatever foreign-keys into those rows (children first), and
 * finally the user row.
 */
@Service
@Slf4j
public class AccountDeletionService {

    private static final int MAX_DEPTH = 8;
    private record Fk(String childTable, String childCol, String parentCol) {}
    private record Schema(Set<String> userTables, Map<String, List<Fk>> children) {}

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AdminSettings adminSettings;
    private volatile Schema schema;

    public AccountDeletionService(JdbcTemplate jdbc, DataSource dataSource, UserRepository users,
                                  PasswordEncoder encoder, AdminSettings adminSettings) {
        this.jdbc = jdbc; this.dataSource = dataSource; this.users = users; this.encoder = encoder; this.adminSettings = adminSettings;
    }

    @Transactional
    public void delete(String email, String password) {
        User user = users.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
        if (password == null || !encoder.matches(password, user.getPassword()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That password is not correct.");
        if (adminSettings.isBootstrapEmail(email))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This account is a console administrator set on the server (ADMIN_EMAILS). Remove it there first.");
        Schema s = schema();
        Long id = user.getId();
        for (String t : s.userTables()) purge(s, t, "user_id = ?", id, 0);
        jdbc.update("DELETE FROM email_otps WHERE email = ?", user.getEmail());
        purge(s, "users", "id = ?", id, 0);
        log.info("Account {} deleted with all associated data", id);
    }

    private void purge(Schema s, String table, String cond, Long param, int depth) {
        if (depth < MAX_DEPTH) {
            for (Fk fk : s.children().getOrDefault(table, List.of())) {
                if (fk.childTable().equals(table)) continue;
                purge(s, fk.childTable(), fk.childCol() + " IN (SELECT " + fk.parentCol() + " FROM " + table + " WHERE " + cond + ")", param, depth + 1);
            }
        }
        jdbc.update("DELETE FROM " + table + " WHERE " + cond, param);
    }

    private Schema schema() {
        Schema s = schema;
        if (s == null) synchronized (this) { if ((s = schema) == null) s = schema = load(); }
        return s;
    }

    private Schema load() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String sch = c.getSchema();
            List<String> tables = new ArrayList<>();
            try (ResultSet r = md.getTables(null, sch, "%", new String[]{"TABLE"})) {
                while (r.next()) tables.add(r.getString("TABLE_NAME"));   // keep the stored case: metadata lookups are case-sensitive
            }
            Set<String> userTables = new LinkedHashSet<>();
            Map<String, List<Fk>> children = new HashMap<>();
            for (String stored : tables) {
                String t = stored.toLowerCase(Locale.ROOT);
                try (ResultSet r = md.getColumns(null, sch, stored, "%")) {
                    while (r.next()) if ("user_id".equalsIgnoreCase(r.getString("COLUMN_NAME")) && !t.startsWith("admin_")) userTables.add(t);
                }
                try (ResultSet r = md.getImportedKeys(null, sch, stored)) {
                    while (r.next()) children.computeIfAbsent(r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Fk(t, r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT), r.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT)));
                }
            }
            return new Schema(userTables, children);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the database layout", e);
        }
    }
}
