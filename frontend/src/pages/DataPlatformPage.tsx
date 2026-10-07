import { useCallback, useEffect, useState } from 'react';
import { Database, ShieldCheck, GitMerge, ListChecks, Upload, RefreshCw, Link2, X, AlertTriangle, CheckCircle2, Users } from 'lucide-react';
import { LoadFailure } from '../components/shared/LoadFailure';
import {
  dataPlatformApi, SOURCE_LABEL, RECON_LABEL,
} from '../api/dataPlatform';
import type {
  ConnectionsOverview, DataHealth, FamilyView, Ownership, LedgerIssue, TxnRow, TxnDetail, SyncRun, ResolutionAction, ReconStatus, SourceType, ImportSummary,
} from '../api/dataPlatform';

type View = 'connections' | 'health' | 'reconcile' | 'transactions' | 'import' | 'family';

const VIEWS: { id: View; label: string; Icon: typeof Database }[] = [
  { id: 'connections', label: 'Data Connections', Icon: Link2 },
  { id: 'health', label: 'Data Health', Icon: ShieldCheck },
  { id: 'reconcile', label: 'Reconciliation', Icon: GitMerge },
  { id: 'transactions', label: 'Transactions', Icon: ListChecks },
  { id: 'import', label: 'Import', Icon: Upload },
  { id: 'family', label: 'Family & sharing', Icon: Users },
];

const RECON_TONE: Record<ReconStatus, string> = {
  VERIFIED: 'text-bull', MATCHED: 'text-bull', PARTIALLY_MATCHED: 'text-neutral', PENDING: 'text-gray-400',
  MISSING: 'text-bear', DUPLICATE: 'text-gray-500', CONFLICT: 'text-bear', UNCONFIRMED: 'text-neutral',
};

const fmtDate = (s: string | null) => (s ? new Date(s).toLocaleDateString('en-IN') : '—');
const fmtDateTime = (s: string | null) => (s ? new Date(s).toLocaleString('en-IN') : '—');
const fmtNum = (n: number | null, d = 2) => (n == null ? '—' : n.toLocaleString('en-IN', { maximumFractionDigits: d }));

function errText(e: unknown): string {
  const r = (e as { response?: { data?: { message?: string; error?: string } } })?.response?.data;
  return r?.message || r?.error || 'Something went wrong. Nothing was changed.';
}

function ReconBadge({ status }: { status: ReconStatus }) {
  return <span className={`text-2xs font-semibold ${RECON_TONE[status]}`}>{RECON_LABEL[status]}</span>;
}

/* ───────────────────────── connections ───────────────────────── */

function Connections() {
  const [data, setData] = useState<ConnectionsOverview | null>(null);
  const [runs, setRuns] = useState<SyncRun[]>([]);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [institution, setInstitution] = useState('');
  const [mobile, setMobile] = useState('');

  const load = useCallback(async () => {
    try {
      const [c, r] = await Promise.all([dataPlatformApi.connections(), dataPlatformApi.syncRuns()]);
      setData(c.data); setRuns(r.data); setFailed(false);
    } catch { setFailed(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const run = async (key: string, fn: () => Promise<unknown>) => {
    setBusy(key); setMsg(null);
    try { await fn(); await load(); } catch (e) { setMsg(errText(e)); } finally { setBusy(null); }
  };

  if (failed && !data) return <LoadFailure what="data connections" onRetry={load} />;
  if (!data) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  return (
    <div className="space-y-4">
      <div className={`rounded-xl border p-3 text-xs ${data.institutionSourceConnected ? 'border-bull/30 bg-bull/5 text-gray-300' : 'border-neutral/30 bg-neutral/5 text-gray-300'}`}>
        {data.institutionSourceConnected
          ? 'An institution source is connected. Its records verify what emails and manual entries report.'
          : data.message}
      </div>
      {msg && <p className="text-xs text-bear">{msg}</p>}

      <div className="card space-y-3">
        <h2 className="text-sm font-bold text-ink">Your connections</h2>
        {data.connections.length === 0 && <p className="text-xs text-gray-500">Nothing connected yet.</p>}
        {data.connections.map(c => (
          <div key={`${c.providerId}-${c.id}`} className="rounded-lg border border-surface-border p-3 space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-semibold text-ink">{c.displayName}</span>
              <span className="text-2xs text-gray-500">{SOURCE_LABEL[c.sourceType]}{c.mode !== 'LIVE' ? ` · ${c.mode}` : ''}</span>
            </div>
            <p className="text-2xs text-gray-400">
              {c.status.replace('_', ' ').toLowerCase()}
              {c.consentStatus ? ` · consent ${c.consentStatus.replace('_', ' ').toLowerCase()}` : ''}
              {` · last successful sync ${fmtDateTime(c.lastSuccessfulSyncAt)}`}
            </p>
            {c.mode === 'MOCK' && <p className="text-2xs text-neutral">Development data — never treated as a verified institution source.</p>}
            {c.note && <p className="text-2xs text-gray-500">{c.note}</p>}
            {c.lastError && <p className="text-2xs text-bear">Last error: {c.lastError}</p>}
            {c.id != null && (
              <div className="flex flex-wrap gap-2 pt-1">
                {c.status === 'PENDING_CONSENT' && c.consentId != null && (
                  <button className="btn-ghost text-2xs py-1 px-2" disabled={busy !== null}
                    onClick={() => run(`r${c.id}`, () => dataPlatformApi.refreshConsent(c.consentId as number))}>
                    Check consent
                  </button>
                )}
                {c.status === 'CONNECTED' && (['INCREMENTAL', 'FULL_RECONCILIATION', 'RETRY_FAILED'] as const).map(k => (
                  <button key={k} className="btn-ghost text-2xs py-1 px-2" disabled={busy !== null}
                    onClick={() => run(`${k}${c.id}`, () => dataPlatformApi.sync(c.id as number, k))}>
                    <RefreshCw size={10} className={busy === `${k}${c.id}` ? 'animate-spin' : ''} />
                    {k === 'INCREMENTAL' ? 'Sync now' : k === 'FULL_RECONCILIATION' ? 'Full reconciliation' : 'Retry failed'}
                  </button>
                ))}
                {c.status !== 'DISCONNECTED' && (
                  <button className="text-2xs text-gray-400 underline hover:text-ink" disabled={busy !== null}
                    onClick={() => run(`d${c.id}`, () => dataPlatformApi.disconnect(c.id as number))}>
                    Disconnect
                  </button>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      {data.availableProviders.length > 0 && (
        <div className="card space-y-2">
          <h2 className="text-sm font-bold text-ink">Add a source</h2>
          <input value={institution} onChange={e => setInstitution(e.target.value)} placeholder="Institution (e.g. HDFC Mutual Fund)"
            className="w-full rounded-lg border border-surface-border bg-transparent px-3 py-1.5 text-xs text-ink" />
          {data.availableProviders.some(p => p.sourceType === 'ACCOUNT_AGGREGATOR' && p.providerId !== 'mock-aa') && (
            <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} inputMode="numeric"
              placeholder="Mobile number linked to your bank accounts (for Account Aggregator)"
              className="w-full rounded-lg border border-surface-border bg-transparent px-3 py-1.5 text-xs text-ink" />
          )}
          {data.availableProviders.map(p => (
            <div key={p.providerId} className="flex items-center justify-between gap-2">
              <span className="text-xs text-gray-300">{p.displayName} <span className="text-2xs text-gray-500">({SOURCE_LABEL[p.sourceType]}{p.mode !== 'LIVE' ? `, ${p.mode}` : ''})</span></span>
              <button className="btn-ghost text-2xs py-1 px-2" disabled={busy !== null || !institution.trim()}
                onClick={() => run(`c${p.providerId}`, async () => {
                  const { data: started } = await dataPlatformApi.startConnection(p.providerId, institution.trim(), mobile || undefined);
                  // The user approves the consent on the aggregator's own page; only https links are opened.
                  if (started?.redirectUrl?.startsWith('https://')) window.open(started.redirectUrl, '_blank', 'noopener,noreferrer');
                })}>
                Connect
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Recent syncs</h2>
        {runs.length === 0 && <p className="text-xs text-gray-500">No syncs yet.</p>}
        {runs.map(r => (
          <div key={r.id} className="text-2xs text-gray-400 flex flex-wrap justify-between gap-2 border-b border-surface-border/50 pb-1">
            <span>{fmtDateTime(r.startedAt)} · {r.kind.replace('_', ' ').toLowerCase()} · <span className={r.status === 'SUCCEEDED' ? 'text-bull' : r.status === 'FAILED' ? 'text-bear' : 'text-neutral'}>{r.status.toLowerCase()}</span></span>
            <span>fetched {r.recordsFetched} · new {r.recordsCreated} · duplicates {r.recordsDuplicated} · rejected {r.recordsRejected}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ───────────────────────── health ───────────────────────── */

function Health() {
  const [data, setData] = useState<DataHealth | null>(null);
  const [failed, setFailed] = useState(false);
  const [scope, setScope] = useState<'self' | 'family'>('self');

  const load = useCallback(async () => {
    try { setData((await dataPlatformApi.health(scope)).data); setFailed(false); } catch { setFailed(true); }
  }, [scope]);
  useEffect(() => { load(); }, [load]);

  if (failed && !data) return <LoadFailure what="data health" onRetry={load} />;
  if (!data) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  const score = data.scorePercent;
  const tone = score == null ? 'text-gray-400' : score >= 90 ? 'text-bull' : score >= 60 ? 'text-neutral' : 'text-bear';
  return (
    <div className="space-y-4">
      <div className="flex justify-end gap-1 text-2xs">
        {(['self', 'family'] as const).map(s => (
          <button key={s} onClick={() => setScope(s)} className={`px-2 py-1 rounded ${scope === s ? 'bg-brand/15 text-brand-light' : 'text-gray-500'}`}>
            {s === 'self' ? 'Mine' : 'Including family'}
          </button>
        ))}
      </div>
      <div className="card space-y-2">
        <div className="flex items-end gap-3">
          <div className={`text-3xl font-mono font-bold ${tone}`}>{score == null ? '—' : `${score.toFixed(0)}%`}</div>
          <div className="text-xs text-gray-400 pb-1">of {data.considered} transactions verified by an institution</div>
        </div>
        <p className="text-2xs text-gray-500">{data.formula}</p>
        {data.warnings.map((w, i) => (
          <p key={i} className="text-xs text-neutral flex items-start gap-1.5"><AlertTriangle size={12} className="shrink-0 mt-0.5" />{w}</p>
        ))}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2 pt-2">
          {([
            ['Verified', data.verified], ['Awaiting confirmation', data.pending], ['Not confirmed', data.unconfirmed],
            ['Missing from records', data.missing], ['Sources disagree', data.conflicts], ['Possible duplicates', data.possibleDuplicates],
            ['Holding mismatches', data.holdingMismatches], ['Duplicates prevented', data.duplicatesPrevented],
          ] as [string, number][]).map(([l, v]) => (
            <div key={l} className="rounded-lg border border-surface-border p-2">
              <div className="stat-label">{l}</div>
              <div className="font-mono font-bold text-ink">{v.toLocaleString('en-IN')}</div>
            </div>
          ))}
        </div>
        <p className="text-2xs text-gray-500">Last verified: {fmtDateTime(data.lastVerifiedAt)}</p>
      </div>

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">By account</h2>
        {data.accounts.length === 0 && <p className="text-xs text-gray-500">No accounts yet.</p>}
        {data.accounts.map(a => (
          <div key={a.accountId} className="flex justify-between text-xs border-b border-surface-border/50 pb-1">
            <span className="text-gray-300">{a.name} <span className="text-2xs text-gray-500">{a.ownership.toLowerCase()}</span></span>
            <span className="text-gray-400">{a.scorePercent == null ? '—' : `${a.scorePercent.toFixed(0)}%`} · {a.verified}/{a.transactions} verified</span>
          </div>
        ))}
      </div>

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Holdings against the institution</h2>
        {data.holdings.length === 0 && <p className="text-xs text-gray-500">No holdings in the ledger yet.</p>}
        {data.holdings.map(h => (
          <div key={`${h.accountId}-${h.assetId}`} className="flex justify-between gap-2 text-xs border-b border-surface-border/50 pb-1">
            <span className="text-gray-300 truncate">{h.assetName ?? h.symbol ?? h.isin}</span>
            <span className="text-gray-400 shrink-0">
              records {fmtNum(h.calculatedQuantity, 4)}{h.reportedQuantity != null ? ` · institution ${fmtNum(h.reportedQuantity, 4)}` : ''} ·{' '}
              <span className={h.state === 'VERIFIED' ? 'text-bull' : h.state === 'NEEDS_RECONCILIATION' ? 'text-bear' : 'text-neutral'}>{h.state.replace('_', ' ').toLowerCase()}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ───────────────────────── reconciliation center ───────────────────────── */

const ACTIONS: { action: ResolutionAction; label: string; hint: string }[] = [
  { action: 'REVIEW', label: 'Review', hint: 'Mark as being looked at' },
  { action: 'CONFIRM', label: 'Confirm', hint: 'Yes, this transaction happened' },
  { action: 'REJECT', label: 'Reject', hint: 'This did not happen — it stays on record as rejected' },
  { action: 'MERGE', label: 'Merge', hint: 'These are the same transaction' },
  { action: 'IGNORE', label: 'Ignore', hint: 'Not a problem' },
  { action: 'MARK_MANUAL_ADJUSTMENT', label: 'Manual adjustment', hint: 'Record the difference as a visible manual entry' },
];

const APPLICABLE: Record<string, ResolutionAction[]> = {
  MISSING_TRANSACTION: ['REVIEW', 'CONFIRM', 'REJECT', 'IGNORE'],
  UNCONFIRMED_TRANSACTION: ['REVIEW', 'CONFIRM', 'REJECT', 'IGNORE'],
  POSSIBLE_DUPLICATE: ['REVIEW', 'MERGE', 'IGNORE'],
  CONFLICTING_SOURCES: ['REVIEW', 'CONFIRM', 'REJECT', 'IGNORE'],
  HOLDING_MISMATCH: ['REVIEW', 'MARK_MANUAL_ADJUSTMENT', 'IGNORE'],
};

function Reconcile({ onOpenTxn }: { onOpenTxn: (id: number) => void }) {
  const [issues, setIssues] = useState<LedgerIssue[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<number, string>>({});

  const load = useCallback(async () => {
    try { setIssues((await dataPlatformApi.issues('open')).data); setFailed(false); } catch { setFailed(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const act = async (i: LedgerIssue, action: ResolutionAction) => {
    setBusy(i.id); setMsg(null);
    try {
      await dataPlatformApi.act(i.id, action, notes[i.id], action === 'MERGE' ? i.otherTransactionId ?? undefined : undefined);
      await load();
    } catch (e) { setMsg(errText(e)); } finally { setBusy(null); }
  };

  if (failed && !issues) return <LoadFailure what="reconciliation issues" onRetry={load} />;
  if (!issues) return <div className="card animate-pulse h-40 bg-surface-hover" />;
  if (issues.length === 0) {
    return (
      <div className="card flex items-center gap-2 text-xs text-gray-300">
        <CheckCircle2 size={14} className="text-bull" /> Nothing needs your decision.
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <p className="text-2xs text-gray-500">Nothing here changes your records until you choose. Rejected and merged transactions are kept, with a note, never deleted.</p>
      {msg && <p className="text-xs text-bear">{msg}</p>}
      {issues.map(i => (
        <div key={i.id} className="card space-y-2">
          <div className="flex items-center justify-between gap-2">
            <span className={`text-2xs font-semibold ${i.severity === 'HIGH' ? 'text-bear' : 'text-neutral'}`}>
              {i.type.replace(/_/g, ' ').toLowerCase()} · {i.severity.toLowerCase()}
            </span>
            <span className="text-2xs text-gray-500">{fmtDate(i.detectedAt)}{i.status === 'IN_REVIEW' ? ' · in review' : ''}</span>
          </div>
          <p className="text-xs font-semibold text-ink">{i.title}</p>
          <p className="text-xs text-gray-300">{i.description}</p>
          {(i.expectedValue || i.observedValue) && (
            <p className="text-2xs text-gray-400">
              Your records: {i.expectedValue ?? '—'} · Institution: {i.observedValue ?? '—'}{i.difference != null ? ` · difference ${fmtNum(i.difference, 4)}` : ''}
            </p>
          )}
          {i.suspectedCauses && <p className="text-2xs text-gray-500">Possible causes: {i.suspectedCauses.replace(/_/g, ' ').toLowerCase().split(',').join(', ')}</p>}
          <input value={notes[i.id] ?? ''} onChange={e => setNotes(n => ({ ...n, [i.id]: e.target.value }))} placeholder="Note (optional)"
            className="w-full rounded-lg border border-surface-border bg-transparent px-2 py-1 text-2xs text-ink" />
          <div className="flex flex-wrap gap-2">
            {ACTIONS.filter(a => (APPLICABLE[i.type] ?? ['REVIEW', 'IGNORE']).includes(a.action)).map(a => (
              <button key={a.action} title={a.hint} className="btn-ghost text-2xs py-1 px-2" disabled={busy === i.id} onClick={() => act(i, a.action)}>
                {a.label}
              </button>
            ))}
            {i.transactionId != null && (
              <button className="text-2xs text-gray-400 underline hover:text-ink" onClick={() => onOpenTxn(i.transactionId as number)}>View transaction &amp; sources</button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/* ───────────────────────── transactions + detail ───────────────────────── */

function TxnDetailPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const [d, setD] = useState<TxnDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [applyMsg, setApplyMsg] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const apply = async () => {
    setApplying(true); setApplyMsg(null);
    try { setApplyMsg((await dataPlatformApi.addToPortfolio(id)).data.message); } catch (e) { setApplyMsg(errText(e)); } finally { setApplying(false); }
  };
  useEffect(() => {
    setD(null); setFailed(false); setApplyMsg(null);
    dataPlatformApi.transaction(id).then(r => setD(r.data)).catch(() => setFailed(true));
  }, [id]);

  return (
    <div className="card space-y-3 border-brand/30">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-ink">Transaction detail</h2>
        <button onClick={onClose} aria-label="Close" className="text-gray-500 hover:text-ink"><X size={14} /></button>
      </div>
      {failed && <LoadFailure what="this transaction" />}
      {!d && !failed && <div className="animate-pulse h-24 bg-surface-hover rounded" />}
      {d && (
        <>
          <div className="text-xs text-gray-300 space-y-0.5">
            <p><span className="font-semibold text-ink">{d.summary.type}</span> · {d.summary.asset ?? '—'} · {fmtDate(d.summary.date)}</p>
            <p>Units {fmtNum(d.summary.quantity, 4)} · price {fmtNum(d.summary.unitPrice)} · amount ₹{fmtNum(d.summary.netAmount)}
              {d.fees != null ? ` · fees ₹${fmtNum(d.fees)}` : ''}{d.taxes != null ? ` · taxes ₹${fmtNum(d.taxes)}` : ''}</p>
            <p>{d.accountInstitution} · {d.summary.account} · {d.ownership?.toLowerCase()}</p>
            <p><ReconBadge status={d.summary.reconciliationStatus} /> · confidence {(d.summary.confidence * 100).toFixed(0)}% · last verified {fmtDateTime(d.lastVerifiedAt)}</p>
            {d.confidenceExplanation && <p className="text-2xs text-gray-500">{d.confidenceExplanation}</p>}
            {d.externalReferences.length > 0 && <p className="text-2xs text-gray-500">References: {d.externalReferences.join(', ')}</p>}
          </div>
          {['MERGER', 'INTEREST', 'DIVIDEND', 'FD_CREATION', 'FD_MATURITY'].includes(d.summary.type) && (
            <div className="space-y-1">
              <button className="btn-ghost text-xs py-1.5 px-3" disabled={applying} onClick={apply}>
                {d.summary.type === 'INTEREST' || d.summary.type === 'DIVIDEND' ? 'Add to my income'
                  : d.summary.type.startsWith('FD_') ? 'Update my deposit tracker' : 'Apply this merger to my portfolio'}
              </button>
              {applyMsg && <p className="text-2xs text-gray-400">{applyMsg}</p>}
            </div>
          )}
          <div className="space-y-1.5">
            <h3 className="stat-label">Reported by ({d.sources.length})</h3>
            {d.sources.map((s, k) => (
              <div key={k} className="rounded-lg border border-surface-border p-2 text-2xs text-gray-400 space-y-0.5">
                <p className="text-gray-300 font-semibold">{SOURCE_LABEL[s.sourceType]}{s.provider ? ` · ${s.provider}` : ''}{s.reference ? ` · ${s.reference}` : ''}</p>
                <p>Reported {s.reportedType ?? '—'} on {fmtDate(s.reportedDate)} · units {fmtNum(s.quantity, 4)} · amount ₹{fmtNum(s.netAmount ?? s.grossAmount)} · record confidence {(s.recordConfidence * 100).toFixed(0)}%</p>
                {s.matchExplanation && <p className="text-gray-500">Why it was linked: {s.matchExplanation}</p>}
              </div>
            ))}
          </div>
          {d.issues.length > 0 && (
            <div className="space-y-1">
              <h3 className="stat-label">Open questions</h3>
              {d.issues.map(i => <p key={i.id} className="text-2xs text-neutral">{i.title} — {i.status.toLowerCase()}</p>)}
            </div>
          )}
          <div className="space-y-1">
            <h3 className="stat-label">History</h3>
            {d.history.map((h, k) => (
              <p key={k} className="text-2xs text-gray-500">{fmtDateTime(h.at)} · {h.actor} · {h.action.toLowerCase().replace(/_/g, ' ')}{h.note ? ` — ${h.note}` : ''}</p>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function Transactions({ openId, setOpenId }: { openId: number | null; setOpenId: (id: number | null) => void }) {
  const [rows, setRows] = useState<TxnRow[] | null>(null);
  const [failed, setFailed] = useState(false);
  const load = useCallback(async () => {
    try { setRows((await dataPlatformApi.transactions({ limit: 200 })).data); setFailed(false); } catch { setFailed(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  if (failed && !rows) return <LoadFailure what="transactions" onRetry={load} />;
  if (!rows) return <div className="card animate-pulse h-40 bg-surface-hover" />;
  return (
    <div className="space-y-3">
      {openId != null && <TxnDetailPanel id={openId} onClose={() => setOpenId(null)} />}
      <div className="card space-y-1">
        {rows.length === 0 && <p className="text-xs text-gray-500">No transactions in the ledger yet.</p>}
        {rows.map(r => (
          <button key={r.id} onClick={() => setOpenId(r.id)}
            className="w-full text-left flex justify-between gap-2 text-xs border-b border-surface-border/50 py-1.5 hover:bg-surface-hover/40">
            <span className="text-gray-300 truncate">{fmtDate(r.date)} · {r.type} · {r.asset ?? '—'}</span>
            <span className="shrink-0 text-gray-400">₹{fmtNum(r.netAmount)} · <ReconBadge status={r.reconciliationStatus} /> · {r.sourceCount} source{r.sourceCount === 1 ? '' : 's'}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

/* ───────────────────────── import ───────────────────────── */

function ImportPanel() {
  const [csv, setCsv] = useState('');
  const [sheet, setSheet] = useState<File | null>(null);
  const [institution, setInstitution] = useState('');
  const [accountId, setAccountId] = useState('');
  const [sourceType, setSourceType] = useState<SourceType>('STATEMENT');
  const [assetClass, setAssetClass] = useState('MUTUAL_FUND');
  const [complete, setComplete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportSummary | null>(null);
  const [backfillMsg, setBackfillMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const onFile = async (f: File | undefined) => {
    if (!f) return;
    if (/\.xlsx?$/i.test(f.name)) { setSheet(f); setCsv(''); } else { setSheet(null); setCsv(await f.text()); }
  };

  const submit = async () => {
    setBusy(true); setErr(null); setResult(null);
    try {
      if (sheet) setResult((await dataPlatformApi.importFile(sheet, { institution, accountId: accountId || undefined, sourceType, assetClass, completeStatement: complete })).data);
      else setResult((await dataPlatformApi.importCsv({ csv, institution, accountId: accountId || undefined, sourceType, assetClass, completeStatement: complete })).data);
    } catch (e) { setErr(errText(e)); } finally { setBusy(false); }
  };

  const backfill = async () => {
    setBusy(true); setErr(null); setBackfillMsg(null);
    try {
      const s = (await dataPlatformApi.backfill()).data;
      setBackfillMsg(`${s.examined} existing records examined: ${s.created} added to the ledger (${s.email} from email, ${s.manual} manual), ${s.alreadyPresent} already there, ${s.rejected} could not be read.`);
    } catch (e) { setErr(errText(e)); } finally { setBusy(false); }
  };

  const input = 'w-full rounded-lg border border-surface-border bg-transparent px-3 py-1.5 text-xs text-ink';
  return (
    <div className="space-y-4">
      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Import a statement (CSV or Excel)</h2>
        <p className="text-2xs text-gray-500">Rows go through the same checks as every other source. Importing the same file twice adds nothing. Needs a date and a transaction-type column.</p>
        <input className={input} placeholder="Institution, e.g. HDFC Mutual Fund" value={institution} onChange={e => setInstitution(e.target.value)} />
        <input className={input} placeholder="Folio / account number (optional)" value={accountId} onChange={e => setAccountId(e.target.value)} />
        <div className="flex gap-2">
          <select className={input} value={sourceType} onChange={e => setSourceType(e.target.value as SourceType)}>
            <option value="STATEMENT">Statement</option><option value="CAS">CAS</option><option value="BROKER_API">Broker report</option><option value="DEPOSITORY">Depository</option>
          </select>
          <select className={input} value={assetClass} onChange={e => setAssetClass(e.target.value)}>
            <option value="MUTUAL_FUND">Mutual fund</option><option value="STOCK">Stock</option><option value="FD">Fixed deposit</option>
          </select>
        </div>
        <label className="flex items-center gap-2 text-2xs text-gray-400">
          <input type="checkbox" checked={complete} onChange={e => setComplete(e.target.checked)} />
          This statement lists every transaction in its period (email transactions it omits will be marked not confirmed)
        </label>
        <input type="file" accept=".csv,.xlsx,.xls,text/csv" onChange={e => onFile(e.target.files?.[0])} className="text-2xs text-gray-400" />
        <button className="btn-ghost text-xs py-1.5 px-3" disabled={busy || !(csv || sheet) || !institution.trim()} onClick={submit}>
          <Upload size={12} /> Import
        </button>
        {err && <p className="text-xs text-bear">{err}</p>}
        {result && (
          <p className="text-xs text-gray-300">
            {result.rows} rows: {result.created} added, {result.duplicated} already on record, {result.rejected} rejected.
            {result.errors.length > 0 && <span className="block text-2xs text-bear mt-1">{result.errors.slice(0, 5).join(' · ')}</span>}
          </p>
        )}
      </div>
      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Bring existing records into the ledger</h2>
        <p className="text-2xs text-gray-500">Copies your existing transactions into the verified ledger as email or manual records. Your current portfolio is not changed, and running it again adds nothing.</p>
        <button className="btn-ghost text-xs py-1.5 px-3" disabled={busy} onClick={backfill}><Database size={12} /> Run migration</button>
        {backfillMsg && <p className="text-xs text-gray-300">{backfillMsg}</p>}
      </div>
    </div>
  );
}

/* ───────────────────────── family & sharing ───────────────────────── */

function Family() {
  const [families, setFamilies] = useState<FamilyView[] | null>(null);
  const [health, setHealth] = useState<DataHealth | null>(null);
  const [failed, setFailed] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [emails, setEmails] = useState<Record<number, string>>({});
  const [coOwner, setCoOwner] = useState<Record<number, string>>({});

  const load = useCallback(async () => {
    try {
      const [f, h] = await Promise.all([dataPlatformApi.families(), dataPlatformApi.health('self')]);
      setFamilies(f.data); setHealth(h.data); setFailed(false);
    } catch { setFailed(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const run = async (fn: () => Promise<unknown>) => {
    setMsg(null);
    try { await fn(); await load(); } catch (e) { setMsg(errText(e)); }
  };

  if (failed && !families) return <LoadFailure what="family settings" onRetry={load} />;
  if (!families || !health) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  return (
    <div className="space-y-4">
      <p className="text-2xs text-gray-500">
        Accounts are private by default. Share one with a family and others see it only while you have sharing switched on; family members can view but not change it. A joint account is visible to, and editable by, its co-owners.
      </p>
      {msg && <p className="text-xs text-bear">{msg}</p>}

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Families</h2>
        {families.length === 0 && <p className="text-xs text-gray-500">You are not in a family yet.</p>}
        {families.map(f => (
          <div key={f.id} className="rounded-lg border border-surface-border p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-ink">{f.name}{f.owner ? ' (you own this)' : ''}</span>
              <label className="flex items-center gap-1.5 text-2xs text-gray-400">
                <input type="checkbox" checked={f.sharesData} onChange={e => run(() => dataPlatformApi.setSharing(f.id, e.target.checked))} />
                Share my family accounts
              </label>
            </div>
            {f.members.map(m => (
              <div key={m.userId} className="flex justify-between text-2xs text-gray-400">
                <span>{m.name ?? `User ${m.userId}`} · {m.role.toLowerCase()} · {m.sharesData ? 'sharing' : 'not sharing'}</span>
                {f.owner && m.role !== 'OWNER' && (
                  <button className="underline hover:text-ink" onClick={() => run(() => dataPlatformApi.removeFamilyMember(f.id, m.userId))}>Remove</button>
                )}
              </div>
            ))}
            {f.owner && (
              <div className="flex gap-2">
                <input value={emails[f.id] ?? ''} onChange={e => setEmails(x => ({ ...x, [f.id]: e.target.value }))} placeholder="Member's Wealth-OS email"
                  className="flex-1 rounded-lg border border-surface-border bg-transparent px-2 py-1 text-2xs text-ink" />
                <button className="btn-ghost text-2xs py-1 px-2" disabled={!(emails[f.id] ?? '').trim()}
                  onClick={() => run(async () => { await dataPlatformApi.addFamilyMember(f.id, (emails[f.id] ?? '').trim()); setEmails(x => ({ ...x, [f.id]: '' })); })}>
                  Add
                </button>
              </div>
            )}
          </div>
        ))}
        <div className="flex gap-2">
          <input value={name} onChange={e => setName(e.target.value)} placeholder="New family name"
            className="flex-1 rounded-lg border border-surface-border bg-transparent px-2 py-1 text-2xs text-ink" />
          <button className="btn-ghost text-2xs py-1 px-2" disabled={!name.trim()}
            onClick={() => run(async () => { await dataPlatformApi.createFamily(name.trim()); setName(''); })}>Create</button>
        </div>
      </div>

      <div className="card space-y-2">
        <h2 className="text-sm font-bold text-ink">Who can see each account</h2>
        {health.accounts.length === 0 && <p className="text-xs text-gray-500">No accounts yet.</p>}
        {health.accounts.map(a => (
          <div key={a.accountId} className="space-y-1 border-b border-surface-border/50 pb-2">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs text-gray-300 truncate">{a.name}</span>
              <select value={a.ownership} className="rounded border border-surface-border bg-transparent px-1 py-0.5 text-2xs text-ink"
                onChange={e => {
                  const o = e.target.value as Ownership;
                  if (o === 'FAMILY') {
                    const owned = families[0];
                    if (!owned) { setMsg('Create or join a family first.'); return; }
                    run(() => dataPlatformApi.setOwnership(a.accountId, o, owned.id));
                  } else if (o === 'JOINT') {
                    const em = (coOwner[a.accountId] ?? '').trim();
                    if (!em) { setMsg('Enter the co-owner\'s email first, then choose Joint.'); return; }
                    run(() => dataPlatformApi.setOwnership(a.accountId, o, undefined, [em]));
                  } else run(() => dataPlatformApi.setOwnership(a.accountId, o));
                }}>
                <option value="INDIVIDUAL">Only me</option>
                <option value="FAMILY">My family{families[0] ? ` (${families[0].name})` : ''}</option>
                <option value="JOINT">Joint with…</option>
              </select>
            </div>
            <input value={coOwner[a.accountId] ?? ''} onChange={e => setCoOwner(x => ({ ...x, [a.accountId]: e.target.value }))}
              placeholder="Co-owner email (for Joint)" className="w-full rounded border border-surface-border bg-transparent px-2 py-0.5 text-2xs text-ink" />
          </div>
        ))}
      </div>
    </div>
  );
}

/* ───────────────────────── page ───────────────────────── */

export function DataPlatformPage({ initialView = 'connections' }: { initialView?: View }) {
  const [view, setView] = useState<View>(initialView);
  const [openTxn, setOpenTxn] = useState<number | null>(null);

  const openFromIssue = (id: number) => { setOpenTxn(id); setView('transactions'); };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Database size={20} />
        </div>
        <div>
          <h1 className="text-lg font-bold text-ink">Data Platform</h1>
          <p className="text-xs text-gray-500">Banks, brokers and statements are the source of truth. Email is a signal until one of them confirms it.</p>
        </div>
      </div>
      <div className="flex flex-wrap gap-1">
        {VIEWS.map(({ id, label, Icon }) => (
          <button key={id} onClick={() => setView(id)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs ${view === id ? 'bg-brand/15 text-brand-light' : 'text-gray-400 hover:text-ink'}`}>
            <Icon size={12} /> {label}
          </button>
        ))}
      </div>
      {view === 'connections' && <Connections />}
      {view === 'health' && <Health />}
      {view === 'reconcile' && <Reconcile onOpenTxn={openFromIssue} />}
      {view === 'transactions' && <Transactions openId={openTxn} setOpenId={setOpenTxn} />}
      {view === 'import' && <ImportPanel />}
      {view === 'family' && <Family />}
    </div>
  );
}
