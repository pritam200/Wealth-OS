import { useEffect, useState, useCallback, useMemo } from 'react';
import { createPortal } from 'react-dom';
import {
  Zap, TrendingUp, TrendingDown, Coins, PauseCircle, Eye, HelpCircle,
  RefreshCw, AlertTriangle, ChevronDown, ChevronUp, ShieldAlert,
  Check, Clock, X, StickyNote, Wallet, FileSearch, ListTodo, PiggyBank,
} from 'lucide-react';
import { todaysActionsApi } from '../../api/todaysActions';
import type {
  TodaysActionsResponse, BuyAction, SellReduceAction, BookProfitAction, HoldAction, WatchAction, NotAnalysed,
} from '../../api/todaysActions';
import { actionsApi } from '../../api/actions';
import type { ActionItem, ActionStatus, ActionType } from '../../api/actions';
import { useMaskedText } from '../../components/shared/Amount';
import { AssetAllocationRing } from '../../components/shared/AssetAllocationRing';
import { AssessmentModal } from '../../components/shared/AssessmentModal';
import { InvestSavingsCard } from '../../components/wealth/InvestSavingsCard';
import type { AnalystAssessment } from '../../api/analyst';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtPct = (n: number | null | undefined) =>
  n == null ? '—' : `${n >= 0 ? '+' : ''}${n.toFixed(1)}%`;

const SNOOZE_DAYS = 7;
const isoInDays = (days: number) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
};

/* ── Action triggers ────────────────────────────────────────────────────────────────────
   Execute records that the user placed the trade with their broker — it does NOT change any
   holding. The portfolio moves only when the resulting transaction is booked (manually or via
   the broker's email import), which is what keeps advice and the ledger independent.        */
function ActionTriggers({ resolved, onResolve, busy }: {
  resolved?: ActionItem; busy: boolean;
  onResolve: (status: ActionStatus, snoozedUntil?: string, note?: string) => void;
}) {
  const [noteOpen, setNoteOpen] = useState(false);
  const [noteText, setNoteText] = useState(resolved?.note ?? '');

  if (resolved && resolved.status !== 'PENDING') {
    const tone = resolved.status === 'EXECUTED' ? 'pill-bull'
      : resolved.status === 'SKIPPED' ? 'pill-muted' : 'pill-neutral';
    const label = resolved.status === 'EXECUTED' ? 'Done'
      : resolved.status === 'SKIPPED' ? 'Not doing it'
      : `Reminder on ${resolved.snoozedUntil ?? '—'}`;
    return (
      <div className="flex items-center gap-2 flex-wrap">
        <span className={tone}>
          {resolved.status === 'EXECUTED' && <Check size={11} />}
          {resolved.status === 'SNOOZED' && <Clock size={11} />}
          {label}
        </span>
        {resolved.note && <span className="text-2xs text-gray-500 italic truncate">“{resolved.note}”</span>}
        <button onClick={() => onResolve('PENDING')} disabled={busy}
                className="text-2xs text-gray-500 hover:text-gray-300 underline">
          Undo
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5 flex-wrap">
        <button onClick={() => onResolve('EXECUTED')} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-bull/10 text-bull border border-bull/25 hover:bg-bull/20 transition-colors disabled:opacity-50">
          <Check size={11} /> I did this
        </button>
        <button onClick={() => onResolve('SNOOZED', isoInDays(SNOOZE_DAYS))} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-neutral/10 text-neutral border border-neutral/25 hover:bg-neutral/20 transition-colors disabled:opacity-50">
          <Clock size={11} /> Remind me in {SNOOZE_DAYS} days
        </button>
        <button onClick={() => onResolve('SKIPPED')} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-surface-hover text-gray-400 border border-surface-border hover:text-gray-200 transition-colors disabled:opacity-50">
          <X size={11} /> Not doing it
        </button>
        <button onClick={() => setNoteOpen(o => !o)} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-medium px-2 py-1 rounded-lg text-gray-500 hover:text-gray-300 transition-colors">
          <StickyNote size={11} /> Add a note
        </button>
      </div>
      {noteOpen && (
        <div className="flex items-center gap-1.5">
          <input value={noteText} onChange={e => setNoteText(e.target.value)}
                 placeholder="Why you're taking (or not taking) this action"
                 className="input-field text-2xs py-1" style={{ minHeight: 28 }} />
          <button onClick={() => { onResolve('PENDING', undefined, noteText); setNoteOpen(false); }}
                  className="btn-secondary text-2xs py-1 px-2 shrink-0">Save</button>
        </div>
      )}
    </div>
  );
}

/** Opens everything behind an action: the reason, each factor read, positives, risks and tax. */
function FullAnalysis({ detail, name, label, cls }: { detail?: AnalystAssessment | null; name: string; label: string; cls: string }) {
  const [open, setOpen] = useState(false);
  if (!detail) return null;
  return (
    <>
      <button onClick={() => setOpen(true)} className="inline-flex items-center gap-1 text-2xs text-brand-light hover:text-ink">
        <FileSearch size={11} /> See full analysis
      </button>
      {open && createPortal(<AssessmentModal name={name} label={label} cls={cls} data={detail} onClose={() => setOpen(false)} />, document.body)}
    </>
  );
}

/** The detail's positives and risks as short bullets — the "why" at a glance. */
function WhyBullets({ detail }: { detail?: AnalystAssessment | null }) {
  const pos = detail?.positives?.slice(0, 2) ?? [];
  const risks = detail?.risks?.slice(0, 2) ?? [];
  if (pos.length + risks.length === 0) return null;
  return (
    <ul className="space-y-0.5 mb-1.5">
      {pos.map((p, i) => <li key={`p${i}`} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-bull">+</span>{p}</li>)}
      {risks.map((r, i) => <li key={`r${i}`} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-bear">−</span>{r}</li>)}
    </ul>
  );
}

/* ── Collapsible section shell ── */
function Section({ title, subtitle, icon, count, tone, children, defaultOpen = true }: {
  title: string; subtitle?: string; icon: React.ReactNode; count: number; tone: string; children: React.ReactNode; defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  if (count === 0) return null;
  return (
    <div className="card">
      <button onClick={() => setOpen(o => !o)} className="flex items-center justify-between w-full">
        <h3 className="section-title mb-0 flex items-center gap-2">
          {icon} {title}
          <span className={`text-2xs px-1.5 py-0.5 rounded-full font-mono ${tone}`}>{count}</span>
        </h3>
        {open ? <ChevronUp size={14} className="text-gray-500" /> : <ChevronDown size={14} className="text-gray-500" />}
      </button>
      {subtitle && <p className="text-xs text-gray-500 mt-1">{subtitle}</p>}
      {open && <div className="mt-4 space-y-2.5">{children}</div>}
    </div>
  );
}

// `confidence` is the measured hit rate of the validated call on this instrument's history —
// not a probability of profit — and is absent when there is no validated call.
function ConfidenceBadge({ confidence }: { confidence: number | null }) {
  if (confidence == null) return null;
  return <span className="pill-muted" title="Share of past identical calls on this instrument that were right after 20 sessions">
    <span className="font-mono tabular-nums">{confidence}%</span> historical hit rate</span>;
}

function AssetTag({ assetType }: { assetType: string }) {
  const label = assetType === 'PORTFOLIO' ? 'Portfolio' : assetType === 'MF' ? 'Fund' : 'Stock';
  return <span className="pill-muted">{label}</span>;
}

/* Queue card shell — neutral surface with a single coloured left rail for direction, so the
   action's meaning is legible without tinting the whole container. */
function QueueCard({ rail, dimmed, children }: { rail: string; dimmed?: boolean; children: React.ReactNode }) {
  return (
    <div className={`rounded-xl border border-surface-border bg-surface-hover/40 p-3 relative overflow-hidden transition-opacity ${dimmed ? 'opacity-55' : ''}`}>
      <span className="absolute left-0 top-0 bottom-0 w-[3px]" style={{ background: rail }} />
      <div className="pl-2">{children}</div>
    </div>
  );
}

function BuyCard({ a, resolved, onResolve, busy }: {
  a: BuyAction; resolved?: ActionItem; busy: boolean;
  onResolve: (s: ActionStatus, until?: string, note?: string) => void;
}) {
  const maskText = useMaskedText();
  return (
    <QueueCard rail="#10B981" dimmed={!!resolved && resolved.status !== 'PENDING'}>
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-ink text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
            <span className="pill-bull">{a.assetType === 'MF' ? 'Add more' : 'Buy more'}</span>
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            Currently <span className="font-mono tabular-nums">{maskText(fmt(a.currentValue))}</span>
            {a.currentPercentOfEquity != null && <> · <span className="font-mono tabular-nums">{a.currentPercentOfEquity.toFixed(1)}%</span> of your {a.assetType === 'MF' ? 'MF' : 'stock'} book</>}
          </div>
        </div>
        <ConfidenceBadge confidence={a.confidence} />
      </div>
      <p className="text-xs text-gray-300 mb-1.5">{a.why}</p>
      <WhyBullets detail={a.detail} />
      <div className="flex items-start gap-1.5 text-2xs text-gray-500 mb-2">
        <ShieldAlert size={11} className="shrink-0 mt-0.5 text-neutral" />
        <span>What could go wrong: {a.risk}</span>
      </div>
      {a.maxAddWithoutBreachingGuideline != null && (
        <div className="text-2xs text-gray-600 border-t border-surface-border pt-1.5 mb-2">
          {a.maxAddWithoutBreachingGuideline > 0
            ? <>You can add up to <span className="text-gray-300 font-mono tabular-nums">{maskText(fmt(a.maxAddWithoutBreachingGuideline))}</span> before this one holding is more than 25% of your portfolio.</>
            : <>This holding is already over 25% of your portfolio — adding more raises the risk of depending on one name.</>}
        </div>
      )}
      <div className="mb-2"><FullAnalysis detail={a.detail} name={a.name} label={a.assetType === 'MF' ? 'ADD MORE' : 'BUY MORE'} cls="text-bull border-bull/40 bg-bull/10" /></div>
      <ActionTriggers resolved={resolved} onResolve={onResolve} busy={busy} />
    </QueueCard>
  );
}

function SellCard({ a, resolved, onResolve, busy }: {
  a: SellReduceAction; resolved?: ActionItem; busy: boolean;
  onResolve: (s: ActionStatus, until?: string, note?: string) => void;
}) {
  const maskText = useMaskedText();
  const actionLabel = a.action === 'FULL_EXIT' ? 'Exit' : a.action === 'SWITCH' ? 'Switch fund' : 'Reduce';
  return (
    <QueueCard rail="#EF4444" dimmed={!!resolved && resolved.status !== 'PENDING'}>
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-ink text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
            <span className="pill-bear">{actionLabel}</span>
          </div>
          <div className="text-2xs text-gray-500 mt-0.5 font-mono tabular-nums">
            {maskText(fmt(a.currentValue))} · {fmtPct(a.pnlPercent)}
          </div>
        </div>
      </div>
      <p className="text-xs text-gray-300 mb-1.5">{a.why}</p>
      <WhyBullets detail={a.detail} />
      {a.taxImpact && (
        <div className="text-2xs text-neutral bg-neutral/5 border border-neutral/20 rounded px-2 py-1 mb-2">{a.taxImpact}</div>
      )}
      <div className="mb-2"><FullAnalysis detail={a.detail} name={a.name} label={actionLabel.toUpperCase()} cls="text-bear border-bear/40 bg-bear/10" /></div>
      <ActionTriggers resolved={resolved} onResolve={onResolve} busy={busy} />
    </QueueCard>
  );
}

function BookProfitCard({ a, resolved, onResolve, busy }: {
  a: BookProfitAction; resolved?: ActionItem; busy: boolean;
  onResolve: (s: ActionStatus, until?: string, note?: string) => void;
}) {
  const maskText = useMaskedText();
  const [showPlan, setShowPlan] = useState(false);
  return (
    <QueueCard rail="#F59E0B" dimmed={!!resolved && resolved.status !== 'PENDING'}>
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-ink text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
            <span className="pill-neutral">Take some profit</span>
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            <span className="font-mono tabular-nums">{maskText(fmt(a.currentValue))}</span> · <span className="text-bull font-mono tabular-nums">{fmtPct(a.pnlPercent)}</span>
            {a.currentProfitAmount != null && <> · profit <span className="font-mono tabular-nums">{maskText(fmt(a.currentProfitAmount))}</span></>}
          </div>
        </div>
      </div>
      <p className="text-xs text-gray-300 mb-1.5">{a.why}</p>
      <WhyBullets detail={a.detail} />
      <div className="flex items-center justify-between bg-surface-card border border-surface-border rounded-lg px-2.5 py-1.5 mb-1.5">
        <span className="text-2xs text-gray-500">Sell this much</span>
        <span className="text-xs font-mono tabular-nums text-ink">
          {a.suggestedBookPercent.toFixed(0)}% = {maskText(fmt(a.suggestedBookAmount))}
        </span>
      </div>
      {a.taxImpact && <div className="text-2xs text-neutral bg-neutral/5 border border-neutral/20 rounded px-2 py-1 mb-1.5">{a.taxImpact}</div>}
      <button onClick={() => setShowPlan(s => !s)} className="text-2xs text-brand-light flex items-center gap-1 mb-2">
        Where to put the money {showPlan ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
      </button>
      {showPlan && (
        <div className="mb-2 space-y-1.5 border-t border-surface-border pt-2">
          {a.reinvestmentPlan.tranches.map((t, i) => (
            <div key={i} className="flex items-center justify-between text-2xs">
              <div className="min-w-0">
                <span className="text-gray-300">{t.label}</span>
                <span className="text-gray-600 ml-1 font-mono tabular-nums">({t.percentOfTotal.toFixed(0)}%)</span>
                <div className="text-gray-600 truncate">{t.condition}</div>
              </div>
              <span className="font-mono tabular-nums text-gray-300 shrink-0 ml-2">{maskText(fmt(t.amount))}</span>
            </div>
          ))}
          <p className="text-2xs text-gray-700 pt-1">{a.reinvestmentPlan.basis}</p>
        </div>
      )}
      <div className="mb-2"><FullAnalysis detail={a.detail} name={a.name} label="TAKE PROFIT" cls="text-neutral border-neutral/40 bg-neutral/10" /></div>
      <ActionTriggers resolved={resolved} onResolve={onResolve} busy={busy} />
    </QueueCard>
  );
}

function HoldCard({ a }: { a: HoldAction }) {
  const maskText = useMaskedText();
  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-2.5 flex items-center justify-between gap-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-ink text-xs font-medium truncate">{a.name}</span>
          <AssetTag assetType={a.assetType} />
        </div>
        <p className="text-2xs text-gray-500">{a.why}</p>
        <FullAnalysis detail={a.detail} name={a.name} label="NO CHANGE" cls="text-gray-300 border-surface-border bg-surface-hover" />
      </div>
      <div className="text-right shrink-0 text-2xs text-gray-500 font-mono tabular-nums">
        {maskText(fmt(a.currentValue))}<br />{fmtPct(a.pnlPercent)}
      </div>
    </div>
  );
}

function WatchCard({ a }: { a: WatchAction }) {
  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-2.5">
      <div className="flex items-center gap-2 mb-0.5 flex-wrap">
        <span className="text-ink text-xs font-medium truncate">{a.name}</span>
        <AssetTag assetType={a.assetType} />
        <span className="pill-info">Keep an eye on</span>
      </div>
      <p className="text-2xs text-gray-400 mb-1">{a.why}</p>
      <p className="text-2xs text-brand-light mb-1">What to look for: {a.condition}</p>
      <FullAnalysis detail={a.detail} name={a.name ?? 'Portfolio'} label="KEEP AN EYE ON" cls="text-neutral border-neutral/40 bg-neutral/10" />
    </div>
  );
}

/* ── Main page ── */
export function Tab16TodaysActions() {
  const maskText = useMaskedText();
  const [data, setData] = useState<TodaysActionsResponse | null>(null);
  const [resolutions, setResolutions] = useState<ActionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      // The queue itself is always recomputed from live data; only the user's dispositions
      // are stored, so a stale recommendation is never served from the database.
      const [actions, recorded] = await Promise.all([
        todaysActionsApi.get(),
        actionsApi.today().catch(() => ({ data: [] as ActionItem[] })),
      ]);
      setData(actions.data);
      setResolutions(recorded.data);
    } catch {
      setError('Could not load today’s actions — the recommendation service may be unavailable.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const resolvedFor = useMemo(() => {
    const map = new Map<string, ActionItem>();
    for (const r of resolutions) map.set(`${r.actionType}:${r.symbol}`, r);
    return map;
  }, [resolutions]);

  const resolve = useCallback(async (
    actionType: ActionType, symbol: string, name: string | undefined, assetType: string | undefined,
    amount: number | null | undefined, status: ActionStatus, snoozedUntil?: string, note?: string,
  ) => {
    const key = `${actionType}:${symbol}`;
    setBusyKey(key);
    try {
      const { data: saved } = await actionsApi.record({
        actionType, symbol, name, assetType,
        amount: amount ?? undefined, status, snoozedUntil, note,
      });
      setResolutions(prev => {
        const rest = prev.filter(p => `${p.actionType}:${p.symbol}` !== key);
        return [...rest, saved];
      });
    } catch {
      setError('Could not save that action — please try again.');
    } finally { setBusyKey(null); }
  }, []);

  const pendingCount = useMemo(() => {
    if (!data) return 0;
    const actionable = [
      ...data.buy.map(a => `BUY:${a.symbol}`),
      ...data.bookProfit.map(a => `BOOK_PROFIT:${a.symbol}`),
      ...data.sellReduce.map(a => `REDUCE:${a.symbol}`),
    ];
    return actionable.filter(k => {
      const r = resolvedFor.get(k);
      return !r || r.status === 'PENDING';
    }).length;
  }, [data, resolvedFor]);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
            <Zap size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-ink mb-0.5">Action Checklist</h2>
            <p className="text-gray-500 text-sm">
              {pendingCount > 0
                ? <>What to do today, and why</>
                : 'Everything for today has been dealt with.'}
            </p>
          </div>
        </div>
        <button onClick={load} className="btn-secondary flex items-center gap-1.5 text-xs" disabled={loading}>
          <RefreshCw size={13} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {loading && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => <div key={i} className="card animate-pulse h-40 bg-surface-hover" />)}
        </div>
      )}

      {!loading && error && (
        <div className="card border-bear/30 flex items-center gap-2 text-sm text-bear">
          <AlertTriangle size={16} /> {error}
        </div>
      )}

      {!loading && data && (() => {
        const doToday = data.buy.length + data.bookProfit.length + data.sellReduce.length;
        const glance = [
          { label: 'to do today', count: pendingCount, tone: 'text-brand-light', target: 'do-today' },
          { label: 'to keep an eye on', count: data.watch.length, tone: 'text-neutral', target: 'watch' },
          { label: 'need no change', count: data.hold.length, tone: 'text-gray-300', target: 'hold' },
          { label: 'could not be checked', count: data.notAnalysed.length, tone: 'text-gray-500', target: 'not-analysed' },
        ].filter(g => g.count > 0);
        const go = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        return (
        <>
          <div className="card">
            <p className="text-sm text-gray-300">
              {pendingCount > 0
                ? <>You have <b className="text-ink">{pendingCount}</b> thing{pendingCount === 1 ? '' : 's'} to decide today. Each one says what to do, why, and how much — open <span className="text-brand-light">See full analysis</span> for everything behind it.</>
                : 'Nothing needs doing today. Your holdings are listed below with the reason each one needs no change.'}
            </p>
            {glance.length > 0 && (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mt-3">
                {glance.map(g => (
                  <button key={g.label} onClick={() => go(g.target)} className="rounded-xl border border-surface-border p-2.5 text-left hover:bg-surface-hover/60">
                    <div className={`text-xl font-mono font-bold ${g.tone}`}>{g.count}</div>
                    <div className="text-2xs text-gray-500">{g.label}</div>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2 space-y-4">
              <div id="do-today" className="card">
                <h3 className="section-title mb-0 flex items-center gap-2"><ListTodo size={15} className="text-brand-light" /> Do today</h3>
                <p className="text-xs text-gray-500 mt-1">Mark each one when you have done it with your broker — nothing here changes your holdings by itself.</p>
                {doToday === 0
                  ? <p className="text-xs text-gray-500 mt-3">Nothing to buy, sell or take profit on today.</p>
                  : (
                    <div className="mt-4 space-y-4">
                      {data.sellReduce.length > 0 && (
                        <div className="space-y-2.5">
                          <div className="text-2xs uppercase tracking-wide text-bear flex items-center gap-1.5"><TrendingDown size={12} /> Sell or reduce</div>
                          {data.sellReduce.map(a => (
                            <SellCard key={a.symbol} a={a}
                                      resolved={resolvedFor.get(`REDUCE:${a.symbol}`)}
                                      busy={busyKey === `REDUCE:${a.symbol}`}
                                      onResolve={(s, until, note) => resolve('REDUCE', a.symbol, a.name, a.assetType, a.currentValue, s, until, note)} />
                          ))}
                        </div>
                      )}
                      {data.bookProfit.length > 0 && (
                        <div className="space-y-2.5">
                          <div className="text-2xs uppercase tracking-wide text-neutral flex items-center gap-1.5"><Coins size={12} /> Take some profit</div>
                          {data.bookProfit.map(a => (
                            <BookProfitCard key={a.symbol} a={a}
                                            resolved={resolvedFor.get(`BOOK_PROFIT:${a.symbol}`)}
                                            busy={busyKey === `BOOK_PROFIT:${a.symbol}`}
                                            onResolve={(s, until, note) => resolve('BOOK_PROFIT', a.symbol, a.name, a.assetType, a.suggestedBookAmount, s, until, note)} />
                          ))}
                        </div>
                      )}
                      {data.buy.length > 0 && (
                        <div className="space-y-2.5">
                          <div className="text-2xs uppercase tracking-wide text-bull flex items-center gap-1.5"><TrendingUp size={12} /> Add to what you hold</div>
                          {data.buy.map(a => (
                            <BuyCard key={a.symbol} a={a}
                                     resolved={resolvedFor.get(`BUY:${a.symbol}`)}
                                     busy={busyKey === `BUY:${a.symbol}`}
                                     onResolve={(s, until, note) => resolve('BUY', a.symbol, a.name, a.assetType, a.maxAddWithoutBreachingGuideline, s, until, note)} />
                          ))}
                        </div>
                      )}
                    </div>
                  )}
              </div>

              <div id="new-money">
                <div className="flex items-center gap-2 px-1 mb-2 text-xs text-gray-500"><PiggyBank size={13} className="text-brand-light" /> Want to invest new money today? Enter the amount — the split also suggests new funds you don't hold yet.</div>
                <InvestSavingsCard />
              </div>

              <div id="watch">
                <Section title="Keep an eye on" subtitle="Nothing to do yet — each says what would change that."
                         icon={<Eye size={15} className="text-brand-light" />}
                         count={data.watch.length} tone="bg-brand/15 text-brand-light" defaultOpen={false}>
                  {data.watch.map((a, i) => <WatchCard key={a.symbol ?? `pf-${i}`} a={a} />)}
                </Section>
              </div>

              <div id="hold">
                <Section title="No change needed" subtitle="Holdings doing their job — the reason is under each one."
                         icon={<PauseCircle size={15} className="text-gray-400" />}
                         count={data.hold.length} tone="bg-surface-hover text-gray-400" defaultOpen={false}>
                  {data.hold.map(a => <HoldCard key={a.symbol} a={a} />)}
                </Section>
              </div>
            </div>

            <div className="space-y-4">
              <div className="card">
                <h3 className="section-title"><Wallet size={15} className="text-brand-light" /> Net Worth</h3>
                <div className="stat-value-hero mb-1">{maskText(fmt(data.portfolioContext.netWorth))}</div>
                <div className="text-2xs text-gray-500 mb-4">
                  Assets <span className="font-mono tabular-nums text-gray-300">{maskText(fmt(data.portfolioContext.totalAssets))}</span>
                  {data.portfolioContext.loansOutstanding > 0 && <> · loans <span className="font-mono tabular-nums text-bear">{maskText(fmt(data.portfolioContext.loansOutstanding))}</span></>}
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="card-flat">
                    <div className="stat-label mb-0.5">Return on investments</div>
                    <div className={`text-sm font-mono tabular-nums font-semibold ${data.portfolioContext.equityPnl >= 0 ? 'text-bull' : 'text-bear'}`}>
                      {fmtPct(data.portfolioContext.equityPnlPercent)}
                    </div>
                  </div>
                  <div className="card-flat">
                    <div className="stat-label mb-0.5">Holdings checked</div>
                    <div className="text-sm font-mono tabular-nums font-semibold text-gray-200">
                      {data.buy.length + data.sellReduce.length + data.bookProfit.length + data.hold.length + data.watch.length}
                    </div>
                  </div>
                </div>
              </div>

              <div className="card">
                <h3 className="section-title"><Coins size={15} className="text-brand-light" /> Where your money is</h3>
                <AssetAllocationRing wealth={data.portfolioContext} />
              </div>

              {data.notAnalysed.length > 0 && (
                <div id="not-analysed" className="card">
                  <h3 className="section-title">
                    <HelpCircle size={15} className="text-neutral" /> Could not be checked
                    <span className="pill-neutral">{data.notAnalysed.length}</span>
                  </h3>
                  <div className="space-y-1.5">
                    {data.notAnalysed.map((a: NotAnalysed) => (
                      <div key={a.symbol} className="text-2xs">
                        <span className="text-gray-300">{a.name}</span>
                        <p className="text-gray-600">{a.reason}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>

          <p className="text-2xs text-gray-600 px-1">{data.scopeNote}</p>
          {data.portfolioContext.dataGaps.length > 0 && (
            <div className="text-2xs text-gray-500 px-1 space-y-0.5">
              {data.portfolioContext.dataGaps.map((g, i) => <p key={i}>• {g}</p>)}
            </div>
          )}

          {data.buy.length + data.sellReduce.length + data.bookProfit.length + data.hold.length + data.watch.length + data.notAnalysed.length === 0 && (
            <div className="card text-center py-12 text-gray-600">
              <p className="text-sm">No holdings to look at yet.</p>
              <p className="text-xs mt-2">Add stocks or mutual funds under Stocks / Mutual Funds to see today's checklist.</p>
            </div>
          )}
        </>
        );
      })()}
    </div>
  );
}
