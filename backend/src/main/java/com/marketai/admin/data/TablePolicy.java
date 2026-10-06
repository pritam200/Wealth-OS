package com.marketai.admin.data;

import java.util.List;

/**
 * What the console may do with one table. Defined in code, reviewed like any other change:
 * only tables listed here are reachable, only {@code columns} are ever selected, only
 * {@code editable} columns can be changed, and a {@code sensitive} column needs a second administrator.
 * Identifiers below are the only ones that ever reach SQL; every value is a bound parameter.
 */
public record TablePolicy(String table, String label, String idColumn, List<Col> columns, String touchColumn) {

    public enum Type { STRING, BOOLEAN, NUMBER, TIMESTAMP }

    public record Col(String name, Type type, boolean searchable, boolean editable, boolean sensitive, int maxLength) {
        public static Col ro(String name, Type type) { return new Col(name, type, false, false, false, 0); }
        public static Col search(String name, Type type) { return new Col(name, type, true, false, false, 0); }
        public static Col edit(String name, int maxLength, boolean searchable) { return new Col(name, Type.STRING, searchable, true, false, maxLength); }
        public static Col sensitiveFlag(String name) { return new Col(name, Type.BOOLEAN, false, true, true, 0); }
    }

    public Col column(String name) { return columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null); }
}
