import { useCallback, useEffect, useState } from 'react';
import { Database, Download, ShieldAlert } from 'lucide-react';
import { reconciliationApi } from '../../api/reconciliation';
import type { AuditClass, AuditFinding, BackupInfo, DataAuditReport } from '../../api/reconciliation';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n || 0);

const CLASS_LABEL: Record<AuditClass, string> = {
  VERIFIED: 'Verified', DUPLICATE: 'Duplicate', MISREAD: 'Misread', CONFLICT: 'Conflict', CORRUPTED: 'Corrupted',
  MISSING: 'Missing', REQUIRES_RECONCILIATION: 'Needs a look',
};
const CLASS_TONE: Record<AuditClass, string> = {
  VERIFIED: 'text-bull', DUPLICATE: 'text-bear', MISREAD: 'text-bear', CONFLICT: 'text-bear', CORRUPTED: 'text-bear',
  MISSING: 'text-neutral', REQUIRES_RECONCILIATION: 'text-neutral',
};
const ENTITY_LABEL: Record<string, string> = {
  EXPENSE: 'Expense', INCOME: 'Income', TRANSACTION: 'Trade', HOLDING: 'Holding',
  FIXED_DEPOSIT: 'FD', RECURRING_DEPOSIT: 'RD',
};

/**
 * The existing-data audit: every stored record classified, read-only. Removing duplicates and
 * misreads is a separate, explicit step — it needs a backup taken first, and only records the
 * audit calls duplicates or misreads can be selected.
 */
export function DataAuditCard() {
  const maskText = useMaskedText();
  const [report, setReport] = useState<DataAuditReport | null>(null);
  const [backups, setBackups] = useState<BackupInfo[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [a, b] = await Promise.all([reconciliationApi.dataAudit(), reconciliationApi.backups()]);
      setReport(a.data);
      setBackups(b.data);
      setError(null);
    } catch {
      setError('The data audit could not be loaded.');
    }
  }, []);
  useEffect(() => { load(); }, [load]);

  const key = (f: AuditFinding) => `${f.entity}:${f.id}`;
  // The server applies the same rule the rebuild does, so what can be ticked is what will be removed.
  const removable = (f: AuditFinding) => f.removable;
  const usableBackup = backups.find(b => b.usable);

  const toggle = (f: AuditFinding) => setSelected(s => {
    const n = new Set(s);
    if (n.has(key(f))) n.delete(key(f)); else n.add(key(f));
    return n;
  });

  const takeBackup = async () => {
    setBusy(true); setMessage(null); setError(null);
    try {
      const { data } = await reconciliationApi.createBackup();
      setMessage(`Backup ${data.id} taken (${Object.values(data.rowCounts).reduce((s, n) => s + n, 0)} rows). Download it before rebuilding if you want a copy.`);
      await load();
    } catch { setError('The backup could not be taken.'); } finally { setBusy(false); }
  };

  const download = async (id: number) => {
    setError(null);
    try {
      const { data } = await reconciliationApi.downloadBackup(id);
      const url = URL.createObjectURL(data);
      const a = document.createElement('a');
      a.href = url; a.download = `wealth-os-backup-${id}.json`; a.click();
      URL.revokeObjectURL(url);
    } catch { setError('The backup could not be downloaded.'); }
  };

  const rebuild = async () => {
    if (!usableBackup || !report) return;
    const remove = report.findings.filter(f => selected.has(key(f))).map(f => ({ entity: f.entity, id: f.id }));
    const ok = window.confirm(`Remove ${remove.length} duplicate or misread record(s) and re-derive holdings from their trades?\n\n`
      + `Backup ${usableBackup.id} (${new Date(usableBackup.createdAt).toLocaleString('en-IN')}) will be used and can't be reused.`);
    if (!ok) return;
    setBusy(true); setMessage(null); setError(null);
    try {
      const { data } = await reconciliationApi.rebuild(usableBackup.id, remove);
      setMessage(data.summary);
      setSelected(new Set());
      await load();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(msg ?? 'The rebuild was refused; nothing was changed.');
    } finally { setBusy(false); }
  };

  if (error && !report) return <div className="card text-xs text-bear">{error}</div>;
  if (!report) return <div className="card animate-pulse h-32 bg-surface-hover" />;

  const classTotals = (Object.keys(CLASS_LABEL) as AuditClass[]).map(c => ({
    c, n: Object.values(report.counts).reduce((s, m) => s + (m[c] ?? 0), 0),
  }));
  const removableMisreads = report.findings.filter(f => f.classification === 'MISREAD' && removable(f));

  return (
    <div className="card space-y-3">
      <h2 className="text-sm font-bold text-ink flex items-center gap-2"><Database size={14} /> Existing data audit</h2>
      <p className="text-2xs text-gray-500">
        Every stored record, checked against its source and the records around it. Read-only — nothing here changes your data
        unless you take a backup and confirm a rebuild below.
      </p>
      <div className="grid grid-cols-3 md:grid-cols-7 gap-2">
        {classTotals.map(({ c, n }) => (
          <div key={c} className="rounded-lg border border-surface-border bg-surface-hover/40 p-2">
            <div className="stat-label truncate">{CLASS_LABEL[c]}</div>
            <div className={`font-mono text-sm font-bold ${n > 0 ? CLASS_TONE[c] : 'text-gray-500'}`}>{n}</div>
          </div>
        ))}
      </div>
      {(report.duplicateExpenseAmount > 0 || report.duplicateIncomeAmount > 0) && (
        <p className="text-2xs text-bear">
          Duplicates overstate spending by {maskText(fmtINR(report.duplicateExpenseAmount))} and income by {maskText(fmtINR(report.duplicateIncomeAmount))}.
        </p>
      )}

      {removableMisreads.length > 0 && (
        <button onClick={() => setSelected(new Set(removableMisreads.map(key)))} className="btn-ghost text-2xs">
          Tick all {removableMisreads.length} removable misreads
        </button>
      )}

      {report.findings.length === 0
        ? <p className="text-xs text-bull">All {report.totalRecords} records verified.</p>
        : (
          <div className="max-h-80 overflow-y-auto space-y-1.5">
            {report.findings.map(f => (
              <label key={key(f)} className="flex items-start gap-2 rounded border border-surface-border/60 p-2 text-2xs">
                {removable(f)
                  ? <input type="checkbox" className="mt-0.5 accent-brand" checked={selected.has(key(f))} onChange={() => toggle(f)} />
                  : <span className="w-3" />}
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className={`font-semibold ${CLASS_TONE[f.classification]}`}>{CLASS_LABEL[f.classification]}</span>
                    <span className="text-gray-400">{ENTITY_LABEL[f.entity] ?? f.entity} #{f.id}</span>
                    {f.label && <span className="text-ink truncate">{f.label}</span>}
                    {f.amount != null && <span className="font-mono text-gray-400 ml-auto">{maskText(fmtINR(f.amount))}</span>}
                  </div>
                  <div className="text-gray-500">{f.date ? `${f.date} · ` : ''}{f.reason}</div>
                  {f.refusalReason && <div className="text-neutral">Not removed by a rebuild: {f.refusalReason}</div>}
                </div>
              </label>
            ))}
          </div>
        )}

      <div className="border-t border-surface-border pt-3 space-y-2">
        <div className="flex items-center gap-2 flex-wrap">
          <button onClick={takeBackup} disabled={busy} className="btn-secondary text-xs">Take backup</button>
          {usableBackup && (
            <button onClick={() => download(usableBackup.id)} className="btn-ghost text-xs flex items-center gap-1">
              <Download size={11} /> Download backup {usableBackup.id}
            </button>
          )}
          <button onClick={rebuild} disabled={busy || !usableBackup}
            className="btn-primary text-xs flex items-center gap-1 ml-auto"
            title={usableBackup ? '' : 'Take a backup first'}>
            <ShieldAlert size={11} /> Rebuild{selected.size > 0 ? ` and remove ${selected.size}` : ''}
          </button>
        </div>
        <p className="text-2xs text-gray-600">
          A rebuild re-derives holdings from their trades and removes only the duplicates and misreads you tick. It needs a backup
          from the last 24 hours, each backup allows one rebuild, and anything the audit no longer calls a duplicate or misread is refused.
        </p>
        {message && <p className="text-2xs text-bull">{message}</p>}
        {error && <p className="text-2xs text-bear">{error}</p>}
      </div>
    </div>
  );
}
