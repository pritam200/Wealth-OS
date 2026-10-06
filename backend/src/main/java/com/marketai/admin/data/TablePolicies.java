package com.marketai.admin.data;

import com.marketai.admin.data.TablePolicy.Col;
import com.marketai.admin.data.TablePolicy.Type;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The allowlist. Deliberately absent: password hashes, refresh tokens, OTP codes, encrypted statement
 * passwords, OAuth tokens and anything holding financial documents. To expose another table, add it
 * here and review the diff; there is no other way to reach data from the console.
 */
@Component
public class TablePolicies {

    private final Map<String, TablePolicy> byTable = List.of(
        new TablePolicy("users", "Customers", "id", List.of(
            Col.ro("id", Type.NUMBER), Col.search("email", Type.STRING), Col.edit("name", 100, true),
            Col.sensitiveFlag("enabled"), Col.ro("email_verified", Type.BOOLEAN),
            Col.ro("created_at", Type.TIMESTAMP), Col.ro("updated_at", Type.TIMESTAMP)), "updated_at"),
        new TablePolicy("portfolios", "Portfolios", "id", List.of(
            Col.ro("id", Type.NUMBER), Col.ro("user_id", Type.NUMBER), Col.edit("name", 100, true),
            Col.edit("description", 500, true), Col.ro("created_at", Type.TIMESTAMP), Col.ro("updated_at", Type.TIMESTAMP)), "updated_at")
    ).stream().collect(Collectors.toUnmodifiableMap(TablePolicy::table, Function.identity()));

    public TablePolicy get(String table) { return table == null ? null : byTable.get(table); }
    public List<TablePolicy> all() { return byTable.values().stream().sorted(java.util.Comparator.comparing(TablePolicy::table)).toList(); }
}
