package com.marketai.common.web;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate sends on the current thread, so a slow request can say
 * how many database round trips it made. Registered as Hibernate's statement inspector; it
 * never changes the SQL.
 */
public class QueryCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    static void reset() { COUNT.get()[0] = 0; }

    static int count() { return COUNT.get()[0]; }
}
