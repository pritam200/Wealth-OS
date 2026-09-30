import { useCallback, useEffect, useState } from 'react';
import { MaskedSentence } from '../components/shared/Amount';
import { ShieldCheck, RefreshCw, AlertTriangle, CheckCircle2, Clock, Mail } from 'lucide-react';
import { reconciliationApi } from '../api/reconciliation';
import type { ReconciliationCenter, StoredIssue } from '../api/reconciliation';
import { LoadFailure } from '../components/shared/LoadFailure';
import { DataAuditCard } from '../components/reconciliation/DataAuditCard';
import { UnresolvedEmailEventsCard } from '../components/reconciliation/UnresolvedEmailEventsCard';

const OUTCOME_LABEL: Record<string, string> = {
  SUCCESS: 'Fully accounted for',
  PARTIAL_SUCCESS: 'Partly read',
  RECONCILIATION_REQUIRED: 'Needs a decision',
  FAILED: 'Failed',
  NO_TRANSACTION: 'Nothing financial',
};

const JOB_LABEL: Record<string, string> = {
  'reconciliation-sweep': 'Nightly reconciliation',
  'reconciliation-after-sync': 'Checks after each sync',
  'mf-nav-refresh': 'Mutual fund NAV refresh',
  'holding-ledger-rebuild': 'Holdings rebuilt from trades',
  'deposit-maturity': 'FD/RD maturity check',
  'gmail-scheduled-sync': 'Scheduled email sync',
  'gmail-retry-sweep': 'Weekly retry of failed emails',
  'gmail-watch-renewal': 'Gmail push renewal',
  'networth-snapshot': 'Daily net worth snapshot',
};

function Stat({ label, value, tone, hint }: { label: string; value: number; tone?: 'bull' | 'bear' | 'neutral'; hint?: string }) {
  const color = tone === 'bull' ? 'text-bull' : tone === 'bear' ? 'text-bear' : tone === 'neutral' ? 'text-neutral' : 'text-ink';
  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3" title={hint}>
      <div className="stat-label mb-1 truncate">{label}</div>
      <div className={`text-lg font-mono tabular-nums font-bold ${color}`}>{value.toLocaleString('en-IN')}</div>
    </div>
  );
}

function IssueRow({ issue, onAcknowledge }: { issue: StoredIssue; onAcknowledge?: (id: number) => void }) {
  const since = new Date(issue.firstSeenAt).toLocaleDateString('en-IN');
  return (
    <div className="rounded-lg border border-surface-border p-3 space-y-1">
      <div className="flex items-center justify-between gap-2">
        <span className={`text-2xs font-semibold ${issue.severity === 'HIGH' ? 'text-bear' : 'text-neutral'}`}>
          {issue.domain} · {issue.type.replace(/_/g, ' ').toLowerCase()}
        </span>
        <span className="text-2xs text-gray-500 shrink-0">
          {issue.status === 'RESOLVED' && issue.resolvedAt
            ? `resolved ${new Date(issue.resolvedAt).toLocaleDateString('en-IN')}`
            : `since ${since}`}
        </span>
      </div>
      <p className="text-xs text-gray-300"><MaskedSentence text={issue.description} /></p>
      {issue.status === 'ACKNOWLEDGED' && (
        <p className="text-2xs text-gray-500">Acknowledged{issue.note ? ` — ${issue.note}` : ''}. Still listed until it is fixed.</p>
      )}
      {issue.status === 'OPEN' && onAcknowledge && (
        <button onClick={() => onAcknowledge(issue.id)} className="text-2xs text-gray-400 underline hover:text-ink">
          Acknowledge
        </button>
      )}
    </div>
  );
}

/**
 * Where every document the app has read, and every event extracted from it, is accounted for:
 * imported, already on record, conflicting, waiting for a decision, or failed — plus the open
 * reconciliation issues and whether the background jobs are running.
 */
export function ReconciliationCenterPage() {
  const [data, setData] = useState<ReconciliationCenter | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (refresh: boolean) => {
    setLoading(true);
    try {
      const { data } = await reconciliationApi.center(refresh);
      setData(data);
      setFailed(false);
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(true); }, [load]);

  const acknowledge = async (id: number) => {
    try {
      await reconciliationApi.acknowledge(id);
      await load(false);
    } catch {
      setFailed(true);
    }
  };

  const header = (
    <div className="flex items-center justify-between gap-3">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <ShieldCheck size={20} />
        </div>
        <div>
          <h1 className="text-lg font-bold text-ink">Reconciliation Center</h1>
          <p className="text-xs text-gray-500">Every document read and every transaction found in it, accounted for.</p>
        </div>
      </div>
      <button onClick={() => load(true)} disabled={loading} className="btn-ghost text-xs py-1.5 px-3">
        <RefreshCw size={12} className={loading ? 'animate-spin' : ''} /> Re-run checks
      </button>
    </div>
  );

  if (failed && !data) return <div className="space-y-4">{header}<LoadFailure what="the reconciliation report" onRetry={() => load(true)} /></div>;
  if (!data) return <div className="space-y-4">{header}<div className="card animate-pulse h-40 bg-surface-hover" /></div>;

  const g = data.ingestion;
  const unaccounted = g.eventsExtracted - g.imported - g.duplicatesPrevented - g.conflicts - g.failedEvents;
  const outcomes = Object.entries(g.documentsByOutcome);
  const troubledJobs = data.backgroundJobs.filter(j => j.lastStatus && j.lastStatus !== 'SUCCEEDED');

  return (
    <div className="space-y-4">
      {header}
      {failed && <LoadFailure what="the latest update" onRetry={() => load(true)} />}

      <div className="card space-y-3">
        <h2 className="text-sm font-bold text-ink flex items-center gap-2"><Mail size={14} /> What has been read</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
          <Stat label="Emails read" value={g.emailsScanned} />
          <Stat label="Statement attachments" value={g.attachments} />
          <Stat label="Transactions found" value={g.eventsExtracted} />
          <Stat label="Imported" value={g.imported} tone="bull" />
          <Stat label="Duplicates prevented" value={g.duplicatesPrevented} hint="Already on record — recognised and not booked again" />
          <Stat label="Conflicts" value={g.conflicts} tone={g.conflicts ? 'bear' : undefined} hint="Same payment reference as a recorded transaction, different details" />
          <Stat label="Waiting for your decision" value={g.needsReview} tone={g.needsReview ? 'neutral' : undefined} />
          <Stat label="Could not be saved" value={g.failedEvents} tone={g.failedEvents ? 'bear' : undefined} hint="Each is also in the review queue with the reason" />
          <Stat label="Emails that failed" value={g.emailsFailed} tone={g.emailsFailed ? 'bear' : undefined} hint="Retried on the next sync" />
          <Stat label="Wrong password" value={g.passwordFailures} tone={g.passwordFailures ? 'bear' : undefined} />
          <Stat label="Waiting for a password" value={g.awaitingPassword} tone={g.awaitingPassword ? 'neutral' : undefined} />
          <Stat label="Scanned, unreadable" value={g.unreadableScans} tone={g.unreadableScans ? 'neutral' : undefined} />
        </div>
        {outcomes.length > 0 && (
          <div className="flex flex-wrap gap-2 text-2xs text-gray-400">
            {outcomes.map(([k, n]) => <span key={k} className="px-2 py-0.5 rounded bg-surface-hover">{OUTCOME_LABEL[k] ?? k}: {n}</span>)}
          </div>
        )}
        {unaccounted > 0 && (
          <p className="text-2xs text-neutral">
            {unaccounted} transaction(s) found were held back before import (unverifiable figures, low confidence or an
            unverified sender) — they are in the review queue.
          </p>
        )}
        {g.documentsWithoutCounts > 0 && (
          <p className="text-2xs text-gray-500">
            {g.documentsWithoutCounts} email(s) were read before per-document counts were kept, so they are not in these totals.
          </p>
        )}
        {data.lastSync && (
          <p className="text-2xs text-gray-500">
            Last sync: {data.lastSync.status.replace(/_/g, ' ').toLowerCase()}
            {data.lastSync.finishedAt ? ` · ${new Date(data.lastSync.finishedAt).toLocaleString('en-IN')}` : ''}
          </p>
        )}
      </div>

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink flex items-center gap-2">
          {data.openIssues.length ? <AlertTriangle size={14} className="text-neutral" /> : <CheckCircle2 size={14} className="text-bull" />}
          Open issues ({data.openIssues.length})
        </h2>
        {data.openIssues.length === 0
          ? <p className="text-xs text-gray-500">Every check passed at {new Date(data.checkedAt).toLocaleTimeString('en-IN')}.</p>
          : data.openIssues.map(i => <IssueRow key={i.id} issue={i} onAcknowledge={acknowledge} />)}
      </div>

      <UnresolvedEmailEventsCard />

      {data.failedEmails.length > 0 && (
        <div className="card space-y-2">
          <h2 className="text-sm font-bold text-ink">Emails that failed ({data.failedEmails.length})</h2>
          {data.failedEmails.map(e => (
            <div key={e.gmailMessageId} className="text-2xs border-b border-surface-border/40 pb-1.5">
              <div className="text-ink truncate">{e.subject || e.sender || e.gmailMessageId}</div>
              <div className="text-gray-500">{e.reason}</div>
            </div>
          ))}
        </div>
      )}

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink flex items-center gap-2"><Clock size={14} /> Background jobs</h2>
        {data.backgroundJobs.length === 0
          ? <p className="text-xs text-gray-500">No background job has reported a run yet.</p>
          : data.backgroundJobs.map(j => (
            <div key={j.jobName} className="flex items-start justify-between gap-3 text-2xs">
              <div className="min-w-0">
                <div className="text-ink">{JOB_LABEL[j.jobName] ?? j.jobName}</div>
                {j.lastStatus !== 'SUCCEEDED' && j.lastError && <div className="text-bear break-words">{j.lastError}</div>}
              </div>
              <div className={`shrink-0 ${j.lastStatus === 'SUCCEEDED' ? 'text-bull' : 'text-bear'}`}>
                {(j.lastStatus ?? 'never run').toLowerCase()}
                {j.lastFinishedAt ? ` · ${new Date(j.lastFinishedAt).toLocaleString('en-IN')}` : ''}
              </div>
            </div>
          ))}
        {troubledJobs.length > 0 && (
          <p className="text-2xs text-neutral">{troubledJobs.length} job(s) did not complete cleanly on their last run.</p>
        )}
      </div>

      <DataAuditCard />

      {data.recentlyResolved.length > 0 && (
        <div className="card space-y-2">
          <h2 className="text-sm font-bold text-ink">Recently resolved</h2>
          {data.recentlyResolved.map(i => <IssueRow key={i.id} issue={i} />)}
        </div>
      )}
    </div>
  );
}
