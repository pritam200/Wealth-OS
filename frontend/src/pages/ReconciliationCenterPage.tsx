import { useCallback, useEffect, useRef, useState } from 'react';
import { MaskedSentence } from '../components/shared/Amount';
import { ShieldCheck, RefreshCw, AlertTriangle, CheckCircle2, Clock, Mail, ChevronDown, ChevronUp, KeyRound, ListChecks, Inbox } from 'lucide-react';
import { reconciliationApi } from '../api/reconciliation';
import type { CheckStatus, ReconciliationCenter, StoredIssue } from '../api/reconciliation';
import { portfolioApi } from '../api/portfolio';
import { LoadFailure } from '../components/shared/LoadFailure';
import { DataAuditCard } from '../components/reconciliation/DataAuditCard';
import { StatementResetCard } from '../components/reconciliation/StatementResetCard';
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
  'holding-price-refresh': 'Holding price refresh',
  'deposit-maturity': 'FD/RD maturity check',
  'gmail-scheduled-sync': 'Scheduled email sync',
  'gmail-retry-sweep': 'Weekly retry of failed emails',
  'gmail-watch-renewal': 'Gmail push renewal',
  'networth-snapshot': 'Daily net worth snapshot',
};

/** Findings a one-click merge fixes; fund findings open the fund holdings, other portfolio ones the stock holdings. */
const MERGEABLE = new Set(['DUPLICATE_SYMBOL', 'DUPLICATE_ISIN']);
const FUND_TYPES = new Set(['MF_CAS_UNIT_MISMATCH']);
const STOCK_HOLDINGS_TAB = 13;
const FUND_HOLDINGS_TAB = 6;
const DATA_SYNC_TAB = 15;
const REVIEW_TAB = 19;

function Stat({ label, value, tone, hint }: { label: string; value: number; tone?: 'bull' | 'bear' | 'neutral'; hint?: string }) {
  const color = tone === 'bull' ? 'text-bull' : tone === 'bear' ? 'text-bear' : tone === 'neutral' ? 'text-neutral' : 'text-ink';
  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3" title={hint}>
      <div className="stat-label mb-1 truncate">{label}</div>
      <div className={`text-lg font-mono tabular-nums font-bold ${color}`}>{value.toLocaleString('en-IN')}</div>
    </div>
  );
}

interface IssueActions {
  acknowledge: (id: number) => void;
  ignore: (id: number, note?: string) => void;
  reopen: (id: number) => void;
  merge: () => void;
  open?: (tab: number) => void;
  busy: boolean;
}

function IssueRow({ issue, actions, grouped }: { issue: StoredIssue; actions?: IssueActions; grouped?: boolean }) {
  const since = new Date(issue.firstSeenAt).toLocaleDateString('en-IN');
  const [noting, setNoting] = useState(false);
  const [note, setNote] = useState('');
  const live = issue.status !== 'RESOLVED';
  const holdingsTab = issue.domain === 'PORTFOLIO' && issue.referenceId != null
    ? (FUND_TYPES.has(issue.type) ? FUND_HOLDINGS_TAB : STOCK_HOLDINGS_TAB) : null;
  return (
    <div className="rounded-lg border border-surface-border p-3 space-y-1">
      <div className="flex items-center justify-between gap-2">
        <span className={`text-2xs font-semibold ${issue.severity === 'HIGH' ? 'text-bear' : 'text-neutral'}`}>
          {grouped ? (issue.severity === 'HIGH' ? 'Important' : '') : kindOf(issue.type).title}
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
      {issue.status === 'IGNORED' && (
        <p className="text-2xs text-gray-500">Ignored{issue.note ? ` — ${issue.note}` : ''}. Kept out of the open list while the check keeps finding it.</p>
      )}
      {actions && live && (
        <div className="flex flex-wrap items-center gap-3 pt-0.5">
          {MERGEABLE.has(issue.type) && (
            <button onClick={actions.merge} disabled={actions.busy} className="text-2xs text-brand-light underline hover:text-ink">
              Merge duplicates
            </button>
          )}
          {holdingsTab != null && actions.open && (
            <button onClick={() => actions.open?.(holdingsTab)} className="text-2xs text-brand-light underline hover:text-ink">
              Open holdings
            </button>
          )}
          {issue.status === 'OPEN' && (
            <>
              <button onClick={() => actions.acknowledge(issue.id)} disabled={actions.busy} className="text-2xs text-gray-400 underline hover:text-ink">
                Acknowledge
              </button>
              <button onClick={() => setNoting(n => !n)} disabled={actions.busy} className="text-2xs text-gray-400 underline hover:text-ink">
                Ignore…
              </button>
            </>
          )}
          {(issue.status === 'ACKNOWLEDGED' || issue.status === 'IGNORED') && (
            <button onClick={() => actions.reopen(issue.id)} disabled={actions.busy} className="text-2xs text-gray-400 underline hover:text-ink">
              Reopen
            </button>
          )}
        </div>
      )}
      {noting && issue.status === 'OPEN' && actions && (
        <div className="flex items-center gap-2 pt-1">
          <input value={note} onChange={e => setNote(e.target.value)} placeholder="Why (optional)" maxLength={500}
            className="input-field text-2xs py-1 flex-1 min-w-0" style={{ minHeight: 30 }} />
          <button onClick={() => { actions.ignore(issue.id, note.trim() || undefined); setNoting(false); }}
            disabled={actions.busy} className="btn-ghost text-2xs py-1 px-2">Ignore</button>
        </div>
      )}
    </div>
  );
}

/** Each check and how it came out on the last run. */
function CheckList({ checks }: { checks: CheckStatus[] }) {
  if (checks.length === 0) return null;
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-1">
      {checks.map(c => {
        const state = c.failedToRun ? 'could not run' : c.openIssues ? `${c.openIssues} open` : 'passed';
        const tone = c.failedToRun || c.openIssues ? 'text-bear' : 'text-bull';
        return (
          <div key={c.id} className="flex items-start justify-between gap-2 text-2xs" title={c.description}>
            <span className="text-gray-400 truncate">{c.description}</span>
            <span className={`shrink-0 ${tone}`}>
              {state}{c.ignoredIssues ? ` · ${c.ignoredIssues} ignored` : ''}
            </span>
          </div>
        );
      })}
    </div>
  );
}


/** What each kind of finding means in plain words, and what fixes it. */
const KINDS: Record<string, { title: string; meaning: string; fix?: string }> = {
  NO_PURCHASE_DATE: { title: 'Holdings without a purchase date', meaning: 'Returns (XIRR) and short- or long-term tax cannot be worked out for these.',
    fix: 'Unlock the CAMS / NSDL statement (Data Sync) or upload the broker tradebook — they carry every purchase date.' },
  MISSING_ISIN: { title: 'Holdings without an ISIN', meaning: 'The ISIN is what ties a holding to depository and fund statements, so these cannot be matched.',
    fix: 'Usually filled in by the next depository or CAS statement; open the holding to add it by hand.' },
  TRANSACTION_NEEDS_REVIEW: { title: 'Transactions waiting for your decision', meaning: 'Read from an email but not booked until you confirm them.', fix: 'Decide them in Needs Review.' },
  TRANSACTION_CONFLICT: { title: 'Two records of one payment disagree', meaning: 'The same payment reference was recorded twice with different details.' },
  INGESTION_STATEMENT_TOTALS_MISMATCH: { title: "Statement lines don't add up to the total", meaning: 'A line in the statement may have been missed or misread.',
    fix: 'Compare the statement with what was booked; a missing line can be added by hand.' },
  INGESTION_REVIEW_BACKLOG: { title: 'Review queue is building up', meaning: 'Items left undecided are not in your totals.', fix: 'Decide them in Needs Review.' },
  INGESTION_FAILED_DOCUMENT: { title: 'Statements that could not be read', meaning: 'Nothing from these documents is in your records.' },
  INGESTION_STALLED_DOCUMENT: { title: 'Statements stuck part-way', meaning: 'Reading started but did not finish; they are retried on the next sync.' },
  DEPOSITORY_MOVEMENT_UNBOOKED: { title: 'Share movements with no matching trade', meaning: 'Your depository shows shares moving in or out that no recorded trade explains.',
    fix: 'Usually a trade from a broker whose statement has not been read yet.' },
  DEPOSITORY_MOVEMENT_PART_BOOKED: { title: 'Share movements only partly matched', meaning: 'Some of the quantity moved is explained by trades, the rest is not.' },
  DEPOSITORY_HOLDING_MISMATCH: { title: "Shares don't match your depository statement", meaning: 'The quantity held here differs from the depository.',
    fix: 'Reset the portfolio to the broker statement (Fix data).' },
  DEPOSITORY_HOLDING_NOT_IN_PORTFOLIO: { title: 'Shares at the depository missing here', meaning: 'Held according to the depository, but not in any portfolio.' },
  DEPOSITORY_HOLDING_UNMATCHED: { title: 'Depository holdings not matched', meaning: 'Could not be matched to a holding here.' },
  MF_CAS_UNIT_MISMATCH: { title: "Fund units don't match the CAS", meaning: 'The units held here differ from the consolidated account statement.' },
  MF_NOT_LINKED: { title: 'Funds not linked to AMFI', meaning: 'Without the link, NAVs and fund analysis are unavailable.' },
  NET_WORTH_DRIFT: { title: 'Net worth moved more than expected', meaning: "Today's figure differs from the last snapshot by more than prices explain — often a new import or an edit." },
  DUPLICATE_SYMBOL: { title: 'The same holding entered twice', meaning: 'Two rows for one holding double its value.', fix: 'Merge duplicates.' },
  DUPLICATE_ISIN: { title: 'The same ISIN on two holdings', meaning: 'Two rows for one security double its value.', fix: 'Merge duplicates.' },
  DUPLICATE_FOLIO: { title: 'The same folio on two holdings', meaning: 'One fund folio appears twice.' },
  DUPLICATE_DISPLAY_NAME: { title: 'Two holdings with the same name', meaning: 'May be one holding entered twice.' },
  MISMATCHED_TICKER: { title: "Ticker doesn't match the name", meaning: 'The symbol and the name point to different companies.' },
  NO_CURRENT_PRICE: { title: 'Holdings with no current price', meaning: 'Valued at cost until a price is found.' },
  STALE_PRICE: { title: 'Prices out of date', meaning: 'The last price is several days old.' },
  LEDGER_NEGATIVE_CASH: { title: 'Cash balance below zero', meaning: 'More went out of an account than came in — a deposit is probably missing.' },
  LEDGER_UNCREDITED_PROCEEDS: { title: 'Sale money not credited', meaning: 'A sale or redemption has no matching credit to a bank account.' },
  LEDGER_UNAPPLIED_TRANSFER: { title: 'Transfers not applied', meaning: 'A transfer between your accounts is recorded on one side only.' },
  CARD_STATEMENT_ARITHMETIC_MISMATCH: { title: "Card statement doesn't add up", meaning: 'Opening balance, spends and payments do not give the stated due.' },
  FD_UNLINKED_RENEWAL: { title: 'FD renewals not linked', meaning: 'A renewed deposit may be counted twice.' },
  RD_UNLINKED_RENEWAL: { title: 'RD renewals not linked', meaning: 'A renewed deposit may be counted twice.' },
  MATURED_IDLE: { title: 'Matured deposits not closed', meaning: 'Past maturity but still counted as held.' },
  INVESTMENT_PLAN_FUNDING_SHORTFALL: { title: 'Planned investments short of cash', meaning: 'The source account cannot fund this month’s plan.' },
  CHECK_FAILED: { title: 'Checks that could not run', meaning: 'An error stopped the check; its result is unknown, not clean.' },
};

function kindOf(type: string) {
  return KINDS[type] ?? { title: type.replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase()), meaning: '' };
}

const SHOWN = 3;

/** One kind of finding: what it means and what fixes it, with the first few and the rest on request. */
function IssueGroup({ type, issues, actions }: { type: string; issues: StoredIssue[]; actions: IssueActions }) {
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState(false);
  const k = kindOf(type);
  const high = issues.some(i => i.severity === 'HIGH');
  const shown = all ? issues : issues.slice(0, SHOWN);
  const holdingsTab = issues[0]?.domain === 'PORTFOLIO' ? (FUND_TYPES.has(type) ? FUND_HOLDINGS_TAB : STOCK_HOLDINGS_TAB) : null;
  const goReview = type === 'TRANSACTION_NEEDS_REVIEW' || type === 'INGESTION_REVIEW_BACKLOG';
  return (
    <div className="rounded-xl border border-surface-border">
      <button onClick={() => setOpen(o => !o)} className="w-full flex items-start justify-between gap-3 p-3 text-left">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${high ? 'bg-bear' : 'bg-neutral'}`} />
            <span className="text-sm text-ink font-medium">{k.title}</span>
          </div>
          {k.meaning && <p className="text-xs text-gray-500 mt-0.5">{k.meaning}</p>}
          {k.fix && <p className="text-xs text-brand-light mt-0.5">{k.fix}</p>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span className={`text-sm font-mono font-bold ${high ? 'text-bear' : 'text-neutral'}`}>{issues.length}</span>
          {open ? <ChevronUp size={14} className="text-gray-500" /> : <ChevronDown size={14} className="text-gray-500" />}
        </div>
      </button>
      {(MERGEABLE.has(type) || (holdingsTab != null && actions.open) || (goReview && actions.open)) && (
        <div className="flex flex-wrap gap-3 px-3 pb-2 -mt-1">
          {MERGEABLE.has(type) && <button onClick={actions.merge} disabled={actions.busy} className="text-2xs text-brand-light underline">Merge duplicates</button>}
          {goReview && actions.open && <button onClick={() => actions.open?.(REVIEW_TAB)} className="text-2xs text-brand-light underline">Open Needs Review</button>}
          {!goReview && holdingsTab != null && actions.open && <button onClick={() => actions.open?.(holdingsTab)} className="text-2xs text-brand-light underline">Open holdings</button>}
        </div>
      )}
      {open && (
        <div className="px-3 pb-3 space-y-2">
          {shown.map(i => <IssueRow key={i.id} issue={i} actions={actions} grouped />)}
          {issues.length > SHOWN && (
            <button onClick={() => setAll(a => !a)} className="text-2xs text-gray-400 underline">
              {all ? 'Show fewer' : `Show all ${issues.length}`}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/** Issues grouped by kind, most important and most numerous first. */
function groupIssues(issues: StoredIssue[]): [string, StoredIssue[]][] {
  const by = new Map<string, StoredIssue[]>();
  issues.forEach(i => by.set(i.type, [...(by.get(i.type) ?? []), i]));
  const weight = (l: StoredIssue[]) => (l.some(i => i.severity === 'HIGH') ? 1e6 : 0) + l.length;
  return [...by.entries()].sort((a, b) => weight(b[1]) - weight(a[1]));
}

function ago(iso: string | null): string {
  if (!iso) return 'never';
  const min = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min} min ago`;
  if (min < 48 * 60) return `${Math.round(min / 60)} h ago`;
  return new Date(iso).toLocaleDateString('en-IN');
}

type Tab = 'todo' | 'fix' | 'activity' | 'history';
const STALE_MS = 15 * 60_000;

/**
 * Where every document the app has read, and every event extracted from it, is accounted for.
 * The first screen says what needs the user, in plain words; the numbers, checks, jobs and
 * history are a tab away.
 */
export function ReconciliationCenterPage({ onNavigate }: { onNavigate?: (tab: number) => void } = {}) {
  const [data, setData] = useState<ReconciliationCenter | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionNote, setActionNote] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('todo');
  const [moreNumbers, setMoreNumbers] = useState(false);
  const poll = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** Shows what is stored at once; while checks run in the background, asks again every few seconds. */
  const load = useCallback(async (refresh: boolean) => {
    if (poll.current) { clearTimeout(poll.current); poll.current = null; }
    setLoading(true);
    try {
      const { data } = await reconciliationApi.center(refresh);
      setData(data);
      setFailed(false);
      if (data.refreshing) poll.current = setTimeout(() => load(false), 3000);
      return data;
    } catch {
      setFailed(true);
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(false).then(d => {
      // Checks older than a quarter of an hour are re-run behind the page, not before it.
      if (d && !d.refreshing && (!d.checkedAt || Date.now() - new Date(d.checkedAt).getTime() > STALE_MS)) load(true);
    });
    return () => { if (poll.current) clearTimeout(poll.current); };
  }, [load]);

  /** Runs one action on an issue; a failure is shown on its own and leaves the report in place. */
  const act = async (what: string, run: () => Promise<string | void>, refresh = false) => {
    setBusy(true);
    setActionError(null);
    setActionNote(null);
    try {
      const done = await run();
      if (done) setActionNote(done);
      await load(refresh);
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setActionError(`Could not ${what}${msg ? `: ${msg}` : '.'}`);
    } finally {
      setBusy(false);
    }
  };

  const actions: IssueActions = {
    acknowledge: id => act('acknowledge the issue', async () => { await reconciliationApi.acknowledge(id); }),
    ignore: (id, note) => act('ignore the issue', async () => { await reconciliationApi.ignore(id, note); }),
    reopen: id => act('reopen the issue', async () => { await reconciliationApi.reopen(id); }),
    merge: () => act('merge the duplicates', async () => {
      const { data: m } = await portfolioApi.mergeDuplicateSymbols();
      return `Merged ${m.holdingsMerged} holding(s) in ${m.groupsMerged} group(s).`
        + (m.skipped?.length ? ` Not merged: ${m.skipped.join('; ')}` : '');
    }, true),
    open: onNavigate,
    busy,
  };

  const running = !!data?.refreshing;
  const header = (
    <div className="flex items-center justify-between gap-3 flex-wrap">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <ShieldCheck size={20} />
        </div>
        <div>
          <h1 className="text-lg font-bold text-ink">Reconciliation Center</h1>
          <p className="text-xs text-gray-500">
            {running ? 'Running the checks…' : data ? `Checked ${ago(data.checkedAt)}` : 'Every document read and every transaction found in it, accounted for.'}
          </p>
        </div>
      </div>
      <button onClick={() => load(true)} disabled={running || (loading && !data)} className="btn-ghost text-xs py-1.5 px-3">
        <RefreshCw size={12} className={running ? 'animate-spin' : ''} /> {running ? 'Checking…' : 'Run checks now'}
      </button>
    </div>
  );

  if (failed && !data) return <div className="space-y-4">{header}<LoadFailure what="the reconciliation report" onRetry={() => load(false)} /></div>;
  if (!data) return <div className="space-y-4">{header}<div className="card animate-pulse h-40 bg-surface-hover" /></div>;

  const g = data.ingestion;
  const unaccounted = g.eventsExtracted - g.imported - g.duplicatesPrevented - g.conflicts - g.failedEvents;
  const outcomes = Object.entries(g.documentsByOutcome);
  const open = data.openIssues.filter(i => i.status === 'OPEN');
  const acknowledged = data.openIssues.filter(i => i.status === 'ACKNOWLEDGED');
  const troubledJobs = data.backgroundJobs.filter(j => j.lastStatus && j.lastStatus !== 'SUCCEEDED');
  const groups = groupIssues(open);
  const passwords = g.awaitingPassword + g.passwordFailures;

  const needs: { label: string; count: number; Icon: typeof Inbox; go: () => void; tone: string }[] = [
    { label: open.length === 1 ? 'issue to look at' : 'issues to look at', count: open.length, Icon: AlertTriangle, go: () => setTab('todo'), tone: 'text-bear' },
    { label: 'transactions waiting for your decision', count: g.needsReview, Icon: Inbox, go: () => onNavigate ? onNavigate(REVIEW_TAB) : setTab('todo'), tone: 'text-neutral' },
    { label: passwords === 1 ? 'statement needs a password' : 'statements need a password', count: passwords, Icon: KeyRound, go: () => onNavigate ? onNavigate(DATA_SYNC_TAB) : setTab('activity'), tone: 'text-neutral' },
    { label: data.failedEmails.length === 1 ? 'email could not be read' : 'emails could not be read', count: data.failedEmails.length, Icon: Mail, go: () => setTab('todo'), tone: 'text-bear' },
    { label: troubledJobs.length === 1 ? 'background job had a problem' : 'background jobs had a problem', count: troubledJobs.length, Icon: Clock, go: () => setTab('activity'), tone: 'text-bear' },
  ].filter(n => n.count > 0);

  const TABS: { id: Tab; label: string; badge?: number }[] = [
    { id: 'todo', label: 'To do', badge: open.length + data.failedEmails.length },
    { id: 'fix', label: 'Fix data' },
    { id: 'activity', label: 'Activity' },
    { id: 'history', label: 'History', badge: data.ignoredIssues.length + acknowledged.length || undefined },
  ];

  return (
    <div className="space-y-4">
      {header}
      {failed && <LoadFailure what="the latest update" onRetry={() => load(false)} />}

      <div className="card">
        <h2 className="text-sm font-bold text-ink mb-2 flex items-center gap-2"><ListChecks size={14} /> What needs you</h2>
        {needs.length === 0
          ? <p className="text-sm text-bull flex items-center gap-2"><CheckCircle2 size={14} /> Nothing — every check passed and everything read is accounted for.</p>
          : (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
              {needs.map(n => (
                <button key={n.label} onClick={n.go} className="flex items-center gap-3 rounded-xl border border-surface-border p-3 text-left hover:bg-surface-hover/60">
                  <n.Icon size={16} className={n.tone} />
                  <span className={`text-lg font-mono font-bold ${n.tone}`}>{n.count.toLocaleString('en-IN')}</span>
                  <span className="text-xs text-gray-300">{n.label}</span>
                </button>
              ))}
            </div>
          )}
        {actionError && <p className="text-2xs text-bear mt-2">{actionError}</p>}
        {actionNote && <p className="text-2xs text-bull mt-2">{actionNote}</p>}
      </div>

      <div className="flex gap-1 border-b border-surface-border">
        {TABS.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            className={`px-3 py-2 text-xs -mb-px border-b-2 ${tab === t.id ? 'border-brand text-ink font-semibold' : 'border-transparent text-gray-500 hover:text-ink'}`}>
            {t.label}{t.badge ? <span className="ml-1.5 text-2xs px-1.5 rounded bg-surface-hover text-gray-400">{t.badge}</span> : null}
          </button>
        ))}
      </div>

      {tab === 'todo' && (
        <div className="space-y-4">
          <div className="card space-y-2">
            <h2 className="text-sm font-bold text-ink flex items-center gap-2">
              {open.length ? <AlertTriangle size={14} className="text-neutral" /> : <CheckCircle2 size={14} className="text-bull" />}
              Issues found by the checks
            </h2>
            {groups.length === 0
              ? <p className="text-xs text-gray-500">{data.checkedAt ? 'Every check passed.' : running ? 'The checks are running for the first time…' : 'The checks have not run yet.'}</p>
              : groups.map(([type, list]) => <IssueGroup key={type} type={type} issues={list} actions={actions} />)}
          </div>

          <UnresolvedEmailEventsCard />

          {data.failedEmails.length > 0 && (
            <div className="card space-y-2">
              <h2 className="text-sm font-bold text-ink">Emails that could not be read ({data.failedEmails.length})</h2>
              <p className="text-2xs text-gray-500">Each is tried again on the next sync.</p>
              {data.failedEmails.map(e => (
                <div key={e.gmailMessageId} className="text-2xs border-b border-surface-border/40 pb-1.5">
                  <div className="text-ink truncate">{e.subject || e.sender || e.gmailMessageId}</div>
                  <div className="text-gray-500">{e.reason}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {tab === 'fix' && (
        <div className="space-y-4">
          <StatementResetCard />
          <DataAuditCard />
        </div>
      )}

      {tab === 'activity' && (
        <div className="space-y-4">
          <div className="card space-y-3">
            <h2 className="text-sm font-bold text-ink flex items-center gap-2"><Mail size={14} /> What has been read</h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
              <Stat label="Emails read" value={g.emailsScanned} />
              <Stat label="Transactions found" value={g.eventsExtracted} />
              <Stat label="Imported" value={g.imported} tone="bull" />
              <Stat label="Duplicates prevented" value={g.duplicatesPrevented} hint="Already on record — recognised and not booked again" />
            </div>
            <button onClick={() => setMoreNumbers(m => !m)} className="text-2xs text-gray-400 underline flex items-center gap-1">
              {moreNumbers ? <ChevronUp size={11} /> : <ChevronDown size={11} />} {moreNumbers ? 'Fewer numbers' : 'More numbers'}
            </button>
            {moreNumbers && (
              <>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
                  <Stat label="Statement attachments" value={g.attachments} />
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
              </>
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
            <h2 className="text-sm font-bold text-ink flex items-center gap-2"><ListChecks size={14} /> Checks</h2>
            <p className="text-2xs text-gray-500">{data.checkedAt ? `Last run ${new Date(data.checkedAt).toLocaleString('en-IN')}` : 'Not run yet.'}</p>
            <CheckList checks={data.checks} />
          </div>

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
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="space-y-4">
          {acknowledged.length > 0 && (
            <div className="card space-y-2">
              <h2 className="text-sm font-bold text-ink">Acknowledged ({acknowledged.length})</h2>
              <p className="text-2xs text-gray-500">Seen and accepted for now; they leave this list once fixed.</p>
              {acknowledged.map(i => <IssueRow key={i.id} issue={i} actions={actions} />)}
            </div>
          )}
          {data.ignoredIssues.length > 0 && (
            <div className="card space-y-2">
              <h2 className="text-sm font-bold text-ink">Ignored ({data.ignoredIssues.length})</h2>
              {data.ignoredIssues.map(i => <IssueRow key={i.id} issue={i} actions={actions} />)}
            </div>
          )}
          <div className="card space-y-2">
            <h2 className="text-sm font-bold text-ink">Recently resolved</h2>
            {data.recentlyResolved.length === 0
              ? <p className="text-xs text-gray-500">Nothing resolved recently.</p>
              : data.recentlyResolved.map(i => <IssueRow key={i.id} issue={i} />)}
          </div>
        </div>
      )}
    </div>
  );
}
