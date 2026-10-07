-- Rolls back the canonical data platform (PostgreSQL). NOT run automatically.
--
-- The platform only ADDS tables; every legacy table (users, portfolios, holdings, transactions,
-- income, expense, ...) is the working copy and is untouched, so dropping these returns the app to
-- its previous behaviour. Rows the user confirmed into the portfolio via "Confirm" live in the
-- legacy `transactions` table with provenance source_fingerprint like 'canonical-%'; they are
-- ordinary portfolio rows and are deliberately kept. Review the SELECT below first.
--
--   SELECT count(*) FROM transactions WHERE source_fingerprint LIKE 'canonical-%';
--
-- Take a backup first (pg_dump). Run inside a transaction and check before COMMIT.
BEGIN;
DROP TABLE IF EXISTS transaction_sources        CASCADE;
DROP TABLE IF EXISTS transaction_candidates     CASCADE;
DROP TABLE IF EXISTS holding_snapshots          CASCADE;
DROP TABLE IF EXISTS ledger_issues              CASCADE;
DROP TABLE IF EXISTS ledger_audit_events        CASCADE;
DROP TABLE IF EXISTS canonical_transactions     CASCADE;
DROP TABLE IF EXISTS raw_financial_data         CASCADE;
DROP TABLE IF EXISTS financial_sync_runs        CASCADE;
DROP TABLE IF EXISTS consent_records            CASCADE;
DROP TABLE IF EXISTS data_connections           CASCADE;
DROP TABLE IF EXISTS account_members            CASCADE;
DROP TABLE IF EXISTS family_members             CASCADE;
DROP TABLE IF EXISTS families                   CASCADE;
DROP TABLE IF EXISTS financial_accounts         CASCADE;
DROP TABLE IF EXISTS financial_assets           CASCADE;
-- COMMIT;   -- uncomment after checking
