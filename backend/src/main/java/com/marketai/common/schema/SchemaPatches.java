package com.marketai.common.schema;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Schema changes Hibernate's {@code ddl-auto: update} cannot make on an existing PostgreSQL
 * database: it adds tables and columns, but never widens a column, relaxes NOT NULL, or rewrites
 * the check constraint it generated for an enum column — so a new enum value fails every insert
 * on a database created before the value existed.
 *
 * <p>Runs once the entity manager has applied its own update. Every statement is idempotent and
 * safe to re-run on every start. Skipped on anything other than PostgreSQL (the H2 test database
 * is created fresh from the entities).
 */
@Slf4j
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
public class SchemaPatches {

    static final List<String> POSTGRES = List.of(
        // Sync job outcomes PARTIAL_SUCCESS / RECONCILIATION_REQUIRED
        "ALTER TABLE sync_jobs ALTER COLUMN status TYPE varchar(30)",
        "ALTER TABLE sync_jobs DROP CONSTRAINT IF EXISTS sync_jobs_status_check",
        // Decrypted statement text (PAN, account numbers) is no longer kept
        "UPDATE pending_pdfs SET text_snippet = NULL WHERE text_snippet IS NOT NULL",
        // Plan-match evidence is derived (it can be re-matched at any time), so duplicate rows
        // from concurrent plan reads are removed before the uniqueness the entity declares is
        // enforced. Hibernate only adds a unique constraint when creating the table.
        "DELETE FROM investment_reconciliations a USING investment_reconciliations b "
            + "WHERE a.id > b.id AND a.source_kind = b.source_kind AND a.source_ref = b.source_ref",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_investment_reconciliation_source "
            + "ON investment_reconciliations (source_kind, source_ref)",

        // Expenses/incomes were unique per email, so every line of a statement after the first
        // failed to insert. The key is now the email plus the line's fingerprint (declared on the
        // entities); the old constraint is dropped.
        "ALTER TABLE expenses DROP CONSTRAINT IF EXISTS uq_expense_user_source_email",
        "DROP INDEX IF EXISTS uq_expense_user_source_email",
        "ALTER TABLE incomes DROP CONSTRAINT IF EXISTS uq_income_user_source_email",
        "DROP INDEX IF EXISTS uq_income_user_source_email",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_expense_user_source_line "
            + "ON expenses (user_id, source_email_id, source_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_income_user_source_line "
            + "ON incomes (user_id, source_email_id, source_fingerprint)",

        // One rent row per schedule per month. Duplicate unpaid placeholders (written by
        // concurrent page loads) are derived and removed; a second *paid* row for the same
        // schedule and month is kept as a one-off payment (schedule_id cleared), never deleted.
        "DELETE FROM rents a USING rents b WHERE a.id <> b.id AND a.user_id = b.user_id "
            + "AND a.schedule_id = b.schedule_id AND a.rent_month = b.rent_month AND a.paid_date IS NULL "
            + "AND (b.paid_date IS NOT NULL OR b.id < a.id)",
        "UPDATE rents a SET schedule_id = NULL FROM rents b WHERE a.id > b.id AND a.user_id = b.user_id "
            + "AND a.schedule_id = b.schedule_id AND a.rent_month = b.rent_month",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_rent_schedule_month ON rents (user_id, schedule_id, rent_month)",

        // A statement's closing balance is recorded once per folio/scheme/date; every resync used
        // to append another copy. The copies are identical readings of the same statement.
        "DELETE FROM cas_balance_snapshots a USING cas_balance_snapshots b WHERE a.id > b.id "
            + "AND a.user_id = b.user_id AND a.folio = b.folio "
            + "AND COALESCE(a.scheme_code, '') = COALESCE(b.scheme_code, '') AND a.as_of_date = b.as_of_date",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_cas_balance_statement "
            + "ON cas_balance_snapshots (user_id, folio, COALESCE(scheme_code, ''), as_of_date)",

        // Before 2026-09-30, accepting a review item queued as UNKNOWN booked nothing, and the
        // email was then treated as resolved — so the transaction was lost. Those emails are
        // marked for re-reading (lines already booked are recognised and not booked twice) and
        // the items go back to the queue. Limited to decisions made before the fix, so it
        // applies once.
        "UPDATE processed_emails p SET status = 'FAILED', "
            + "result_summary = 'Re-read: an earlier review approval of this email recorded nothing' "
            + "FROM email_review_items r WHERE r.user_id = p.user_id AND r.gmail_message_id = p.gmail_message_id "
            + "AND r.status = 'ACCEPTED' AND r.parsed_payload IS NULL AND (r.proposed_type IS NULL OR r.proposed_type = 'UNKNOWN') AND r.resolved_at < TIMESTAMP '2026-09-30 00:00:00'",
        "UPDATE email_review_items r SET status = 'PENDING', resolved_at = NULL, "
            + "resolution_note = 'Reopened: the earlier approval recorded nothing. Please decide again.' "
            + "WHERE r.status = 'ACCEPTED' AND r.parsed_payload IS NULL AND (r.proposed_type IS NULL OR r.proposed_type = 'UNKNOWN') AND r.resolved_at < TIMESTAMP '2026-09-30 00:00:00'",

        // Rows written before optimistic locking have no version; Spring Data would treat them as
        // new and try to insert them again.
        "UPDATE holdings SET version = 0 WHERE version IS NULL",
        "UPDATE fixed_deposits SET version = 0 WHERE version IS NULL",
        "UPDATE recurring_deposits SET version = 0 WHERE version IS NULL",
        "UPDATE rents SET version = 0 WHERE version IS NULL",
        "UPDATE planned_investments SET version = 0 WHERE version IS NULL",
        "UPDATE cash_accounts SET version = 0 WHERE version IS NULL",

        // Prices and NAVs to four decimals. Hibernate's update never changes an existing column's
        // type, and at two decimals a NAV of 157.7043 was stored as 157.70 — about ₹4 on a
        // 10,000-unit folio, and a different figure from the fund house's. Widening the scale
        // keeps every stored value exactly; nothing is rounded.
        "ALTER TABLE holdings ALTER COLUMN current_price TYPE numeric(18,4)",
        "ALTER TABLE holdings ALTER COLUMN average_cost TYPE numeric(18,4)",
        "ALTER TABLE transactions ALTER COLUMN price TYPE numeric(18,4)",

        // Bonus and split rows in the trade ledger. Hibernate writes an enum check constraint
        // when it creates a table and never updates it, so the old one would reject them.
        "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_type_check",

        // A statement's content is imported once, however many emails carry it. Copies already
        // marked IMPORTED before this rule are marked as duplicates of the first (the rows are
        // kept, and their transactions are left to the data audit), so the index can be built.
        "UPDATE pending_pdfs p SET status = 'DUPLICATE_DOCUMENT', "
            + "result_summary = 'Same statement content as an earlier imported copy' "
            + "WHERE p.status = 'IMPORTED' AND p.content_hash IS NOT NULL AND EXISTS (SELECT 1 FROM pending_pdfs q "
            + "WHERE q.user_id = p.user_id AND q.content_hash = p.content_hash AND q.status = 'IMPORTED' AND q.id < p.id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_pdf_imported_content "
            + "ON pending_pdfs (user_id, content_hash) WHERE status = 'IMPORTED' AND content_hash IS NOT NULL"
    );

    private final DataSource dataSource;

    @PostConstruct
    void apply() {
        if (!isPostgres()) return;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String sql : POSTGRES) {
            try {
                jdbc.execute(sql);
            } catch (Exception e) {
                log.error("Schema patch failed — '{}': {}", sql, e.getMessage());
            }
        }
    }

    private boolean isPostgres() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("Could not determine the database type; schema patches skipped: {}", e.getMessage());
            return false;
        }
    }
}
