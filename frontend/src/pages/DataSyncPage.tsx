import { useEffect, useState, useCallback } from 'react';
import { RefreshCw, Mail, CheckCircle2, CopyX, AlertTriangle, FileSearch, Inbox, Lock } from 'lucide-react';
import { GmailConnect } from '../components/GmailConnect';
import { ReviewQueue } from '../components/ReviewQueue';
import { SyncNowPanel } from '../components/SyncNowPanel';
import { gmailApi } from '../api/gmail';
import type { GmailStatus, ReconciliationReportDto } from '../api/gmail';
import { reviewApi } from '../api/review';

/** One figure per pipeline stage. `—` where the backend reports the value as undeterminable
 *  rather than zero, so an unknown is never displayed as a confident count. */
function StatCell({ label, value, Icon, tone, hint }: {
  label: string; value: number | string | null; Icon: React.ElementType;
  tone?: 'bull' | 'bear' | 'neutral' | 'muted'; hint?: string;
}) {
  const color = tone === 'bull' ? 'text-bull' : tone === 'bear' ? 'text-bear'
    : tone === 'neutral' ? 'text-neutral' : 'text-gray-200';
  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3" title={hint}>
      <div className="flex items-center gap-1.5 mb-1.5">
        <Icon size={11} className="text-gray-600 shrink-0" />
        <span className="stat-label truncate">{label}</span>
      </div>
      <div className={`text-lg font-mono tabular-nums font-bold ${color}`}>
        {value == null ? '—' : value}
      </div>
    </div>
  );
}

/**
 * Email sync status, read from the persistent reconciliation report so it survives page loads
 * rather than only reflecting the last sync response.
 */
function SyncStatusDashboard() {
  const [status, setStatus] = useState<GmailStatus | null>(null);
  const [report, setReport] = useState<ReconciliationReportDto | null>(null);
  const [pendingReview, setPendingReview] = useState(0);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      gmailApi.getStatus(),
      gmailApi.getReconciliationReport().catch(() => ({ data: null as ReconciliationReportDto | null })),
      reviewApi.count().catch(() => ({ data: { pending: 0 } })),
    ]).then(([s, r, c]) => {
      setStatus(s.data);
      setReport(r.data);
      setPendingReview(c.data.pending);
    }).catch(() => {
      setStatus(null);
    }).finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <div className="card animate-pulse h-32 bg-surface-hover" />;

  if (!status?.connected) {
    return (
      <div className="card">
        <h3 className="section-title"><Mail size={15} className="text-brand-light" /> Sync Status</h3>
        <p className="text-xs text-gray-600">
          Gmail isn't connected yet — connect below to start importing transactions from your
          bank, broker and AMC emails.
        </p>
      </div>
    );
  }

  const scanned = report ? report.imported + report.failed + report.unparsed + report.excluded : null;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
        <h3 className="section-title mb-0"><Mail size={15} className="text-brand-light" /> Sync Status</h3>
        <div className="flex items-center gap-2">
          {status.lastSyncAt && (
            <span className="text-2xs text-gray-500">
              Last sync <span className="font-mono">{new Date(status.lastSyncAt).toLocaleString('en-IN')}</span>
            </span>
          )}
          <button onClick={load} className="btn-icon" title="Refresh"><RefreshCw size={12} /></button>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2">
        <StatCell label="Scanned" value={scanned} Icon={Mail}
                  hint="Emails processed and recorded in sync history" />
        <StatCell label="Imported" value={report?.imported ?? status.importedCount ?? 0} Icon={CheckCircle2} tone="bull" />
        <StatCell label="Duplicates" value={report?.duplicates ?? null} Icon={CopyX} tone="muted"
                  hint={report?.duplicatesNote} />
        <StatCell label="Unparsed" value={report?.unparsed ?? 0} Icon={FileSearch}
                  tone={(report?.unparsed ?? 0) > 0 ? 'neutral' : 'muted'}
                  hint="Financial emails no parser could read" />
        <StatCell label="Needs review" value={pendingReview} Icon={Inbox}
                  tone={pendingReview > 0 ? 'neutral' : 'muted'}
                  hint="Uncertain extractions waiting on your decision" />
        <StatCell label="Failed" value={report?.failed ?? 0} Icon={AlertTriangle}
                  tone={(report?.failed ?? 0) > 0 ? 'bear' : 'muted'} />
      </div>

      {(report?.pdfsAwaitingPassword ?? 0) > 0 && (
        <div className="mt-2.5 flex items-center gap-2 text-2xs text-neutral">
          <Lock size={11} />
          <span className="font-mono tabular-nums">{report!.pdfsAwaitingPassword}</span>
          statement PDF(s) still locked — unlock them below to import their contents.
        </div>
      )}

      <p className="text-2xs text-gray-600 mt-3">
        Failed imports are retried automatically on the next sync, so a transient error no
        longer drops an email permanently. Duplicates are refused by content fingerprint —
        re-syncing or forwarding the same alert cannot double-book a transaction.
      </p>
    </div>
  );
}

export function DataSyncPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <RefreshCw size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Data Sync</h2>
          <p className="text-gray-500 text-xs">Import trades, FDs, SIPs and statements from your bank and broker emails</p>
        </div>
      </div>

      <SyncNowPanel />
      <SyncStatusDashboard />
      <ReviewQueue />
      <GmailConnect />
    </div>
  );
}
