-- Run once as the database owner. Makes admin_audit_events append-only at the database level,
-- so even the application's own database user cannot edit or delete audit history.
CREATE OR REPLACE FUNCTION admin_audit_forbid_change() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'admin_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS admin_audit_no_update ON admin_audit_events;
CREATE TRIGGER admin_audit_no_update BEFORE UPDATE OR DELETE ON admin_audit_events
  FOR EACH ROW EXECUTE FUNCTION admin_audit_forbid_change();
DROP TRIGGER IF EXISTS admin_audit_no_truncate ON admin_audit_events;
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON admin_audit_events
  FOR EACH STATEMENT EXECUTE FUNCTION admin_audit_forbid_change();
