import { useEffect, useState, useCallback } from 'react';
import {
  Zap, TrendingUp, TrendingDown, Coins, PauseCircle, Eye, HelpCircle,
  RefreshCw, AlertTriangle, ChevronDown, ChevronUp, ShieldAlert,
} from 'lucide-react';
import { todaysActionsApi } from '../../api/todaysActions';
import type {
  TodaysActionsResponse, BuyAction, SellReduceAction, BookProfitAction, HoldAction, WatchAction, NotAnalysed,
} from '../../api/todaysActions';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtPct = (n: number | null | undefined) =>
  n == null ? '—' : `${n >= 0 ? '+' : ''}${n.toFixed(1)}%`;

/* ── Collapsible section shell, consistent with the rest of the app ── */
function Section({ title, icon, count, tone, children, defaultOpen = true }: {
  title: string; icon: React.ReactNode; count: number; tone: string; children: React.ReactNode; defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  if (count === 0) return null;
  return (
    <div className="card">
      <button onClick={() => setOpen(o => !o)} className="flex items-center justify-between w-full">
        <h3 className="section-title mb-0 flex items-center gap-2">
          {icon} {title}
          <span className={`text-2xs px-1.5 py-0.5 rounded-full ${tone}`}>{count}</span>
        </h3>
        {open ? <ChevronUp size={14} className="text-gray-500" /> : <ChevronDown size={14} className="text-gray-500" />}
      </button>
      {open && <div className="mt-4 space-y-2.5">{children}</div>}
    </div>
  );
}

function ConfidenceBadge({ confidence }: { confidence: number }) {
  const tone = confidence >= 55 ? 'text-bull bg-bull/10' : confidence >= 25 ? 'text-gold bg-gold/10' : 'text-gray-400 bg-surface-hover';
  return <span className={`text-2xs px-1.5 py-0.5 rounded ${tone}`}>{confidence}% confidence</span>;
}

function AssetTag({ assetType }: { assetType: string }) {
  if (assetType === 'PORTFOLIO') return <span className="text-2xs px-1.5 py-0.5 rounded bg-surface-hover text-gray-400">Portfolio</span>;
  return <span className="text-2xs px-1.5 py-0.5 rounded bg-surface-hover text-gray-400">{assetType === 'MF' ? 'Fund' : 'Stock'}</span>;
}

function BuyCard({ a }: { a: BuyAction }) {
  const maskText = useMaskedText();
  return (
    <div className="rounded-lg border border-bull/20 bg-bull/5 p-3">
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-white text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            Currently {maskText(fmt(a.currentValue))}
            {a.currentPercentOfEquity != null && ` · ${a.currentPercentOfEquity.toFixed(1)}% of your ${a.assetType === 'MF' ? 'MF' : 'stock'} book`}
          </div>
        </div>
        <ConfidenceBadge confidence={a.confidence} />
      </div>
      <p className="text-2xs text-gray-300 mb-1.5">{a.why}</p>
      <div className="flex items-start gap-1.5 text-2xs text-gray-500 mb-1.5">
        <ShieldAlert size={11} className="shrink-0 mt-0.5 text-gold" />
        <span>{a.risk}</span>
      </div>
      {a.maxAddWithoutBreachingGuideline != null && (
        <div className="text-2xs text-gray-600 border-t border-surface-border/40 pt-1.5">
          {a.maxAddWithoutBreachingGuideline > 0
            ? <>Room to add up to <span className="text-gray-300 font-mono">{maskText(fmt(a.maxAddWithoutBreachingGuideline))}</span> before the 25% single-position guideline (not a suggested amount — no cash balance is tracked to size one).</>
            : <>Already at or above the 25% single-position guideline — any addition would increase concentration risk.</>}
        </div>
      )}
    </div>
  );
}

function SellCard({ a }: { a: SellReduceAction }) {
  const maskText = useMaskedText();
  const actionLabel = a.action === 'FULL_EXIT' ? 'Exit' : a.action === 'SWITCH' ? 'Switch fund' : 'Reduce';
  return (
    <div className="rounded-lg border border-bear/20 bg-bear/5 p-3">
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-white text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
            <span className="text-2xs px-1.5 py-0.5 rounded bg-bear/15 text-bear">{actionLabel}</span>
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            {maskText(fmt(a.currentValue))} · {fmtPct(a.pnlPercent)}
          </div>
        </div>
      </div>
      <p className="text-2xs text-gray-300 mb-1.5">{a.why}</p>
      <p className="text-2xs text-gray-500 mb-1">{a.riskReward}</p>
      {a.taxImpact && (
        <div className="text-2xs text-gold/90 bg-gold/5 rounded px-2 py-1 mt-1">{a.taxImpact}</div>
      )}
    </div>
  );
}

function BookProfitCard({ a }: { a: BookProfitAction }) {
  const maskText = useMaskedText();
  const [showPlan, setShowPlan] = useState(false);
  return (
    <div className="rounded-lg border border-gold/20 bg-gold/5 p-3">
      <div className="flex items-start justify-between gap-2 mb-1.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-white text-sm font-semibold truncate">{a.name}</span>
            <AssetTag assetType={a.assetType} />
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            {maskText(fmt(a.currentValue))} · <span className="text-bull">{fmtPct(a.pnlPercent)}</span>
            {a.currentProfitAmount != null && <> · profit {maskText(fmt(a.currentProfitAmount))}</>}
          </div>
        </div>
      </div>
      <p className="text-2xs text-gray-300 mb-2">{a.why}</p>
      <div className="flex items-center justify-between bg-surface-hover/60 rounded px-2.5 py-1.5 mb-1.5">
        <span className="text-2xs text-gray-500">Suggested booking</span>
        <span className="text-xs font-mono text-white">{a.suggestedBookPercent.toFixed(0)}% = {maskText(fmt(a.suggestedBookAmount))}</span>
      </div>
      {a.taxImpact && <div className="text-2xs text-gold/90 bg-gold/5 rounded px-2 py-1 mb-1.5">{a.taxImpact}</div>}
      <button onClick={() => setShowPlan(s => !s)} className="text-2xs text-brand-light flex items-center gap-1">
        Reinvestment plan {showPlan ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
      </button>
      {showPlan && (
        <div className="mt-2 space-y-1.5 border-t border-surface-border/40 pt-2">
          {a.reinvestmentPlan.tranches.map((t, i) => (
            <div key={i} className="flex items-center justify-between text-2xs">
              <div className="min-w-0">
                <span className="text-gray-300">{t.label}</span>
                <span className="text-gray-600 ml-1">({t.percentOfTotal.toFixed(0)}%)</span>
                <div className="text-gray-600 truncate">{t.condition}</div>
              </div>
              <span className="font-mono text-gray-300 shrink-0 ml-2">{maskText(fmt(t.amount))}</span>
            </div>
          ))}
          <p className="text-2xs text-gray-700 pt-1">{a.reinvestmentPlan.basis}</p>
        </div>
      )}
    </div>
  );
}

function HoldCard({ a }: { a: HoldAction }) {
  const maskText = useMaskedText();
  return (
    <div className="rounded-lg border border-surface-border bg-surface-hover/40 p-2.5 flex items-center justify-between gap-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-white text-xs font-medium truncate">{a.name}</span>
          <AssetTag assetType={a.assetType} />
        </div>
        <p className="text-2xs text-gray-500 truncate">{a.why}</p>
      </div>
      <div className="text-right shrink-0 text-2xs text-gray-500">
        {maskText(fmt(a.currentValue))}<br />{fmtPct(a.pnlPercent)}
      </div>
    </div>
  );
}

function WatchCard({ a }: { a: WatchAction }) {
  return (
    <div className="rounded-lg border border-brand/20 bg-brand/5 p-2.5">
      <div className="flex items-center gap-2 mb-0.5">
        <span className="text-white text-xs font-medium truncate">{a.name}</span>
        <AssetTag assetType={a.assetType} />
      </div>
      <p className="text-2xs text-gray-400 mb-1">{a.why}</p>
      <p className="text-2xs text-brand-light">Trigger: {a.condition}</p>
    </div>
  );
}

/* ── Main page ── */
export function Tab16TodaysActions() {
  const maskText = useMaskedText();
  const [data, setData] = useState<TodaysActionsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const { data } = await todaysActionsApi.get();
      setData(data);
    } catch {
      setError('Could not load today’s actions — the recommendation service may be unavailable.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#6d5efc] to-[#00c2ff] flex items-center justify-center text-white shadow-lift shrink-0">
            <Zap size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">Today's Investment Actions</h2>
            <p className="text-gray-500 text-sm">What to do with your money today, across stocks and mutual funds you already hold.</p>
          </div>
        </div>
        <button onClick={load} className="btn-secondary flex items-center gap-1.5 text-xs" disabled={loading}>
          <RefreshCw size={13} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {loading && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => <div key={i} className="card animate-pulse h-20 bg-surface-hover" />)}
        </div>
      )}

      {!loading && error && (
        <div className="card border border-bear/30 bg-bear/5 flex items-center gap-2 text-sm text-bear">
          <AlertTriangle size={16} /> {error}
        </div>
      )}

      {!loading && data && (
        <>
          <div className="card-elevated grid grid-cols-2 lg:grid-cols-5 gap-3">
            <StatTile label="Net Worth" value={maskText(fmt(data.portfolioContext.netWorth))} Icon={TrendingUp} tone="brand" />
            <StatTile label="Equity Allocation" value={data.portfolioContext.equityPercent != null ? `${data.portfolioContext.equityPercent.toFixed(0)}%` : '—'} Icon={Coins} tone="brand" sensitive={false} />
            <StatTile label="Equity P&L" value={fmtPct(data.portfolioContext.equityPnlPercent)} Icon={data.portfolioContext.equityPnl >= 0 ? TrendingUp : TrendingDown} tone={data.portfolioContext.equityPnl >= 0 ? 'bull' : 'bear'} sensitive={false} />
            <StatTile label="Holdings Analysed" value={String(data.buy.length + data.sellReduce.length + data.bookProfit.length + data.hold.length + data.watch.filter(w => w.symbol).length)} Icon={Eye} tone="neutral" sensitive={false} />
            <StatTile label="Not Analysed" value={String(data.notAnalysed.length)} Icon={HelpCircle} tone={data.notAnalysed.length > 0 ? 'gold' : 'neutral'} sensitive={false} />
          </div>

          <p className="text-2xs text-gray-600 px-1">{data.scopeNote}</p>
          {data.portfolioContext.dataGaps.length > 0 && (
            <div className="text-2xs text-gray-500 px-1 space-y-0.5">
              {data.portfolioContext.dataGaps.map((g, i) => <p key={i}>• {g}</p>)}
            </div>
          )}

          <Section title="Buy / Increase" icon={<TrendingUp size={15} className="text-bull" />} count={data.buy.length} tone="bg-bull/15 text-bull">
            {data.buy.map(a => <BuyCard key={a.symbol} a={a} />)}
          </Section>

          <Section title="Book Profit" icon={<Coins size={15} className="text-gold" />} count={data.bookProfit.length} tone="bg-gold/15 text-gold">
            {data.bookProfit.map(a => <BookProfitCard key={a.symbol} a={a} />)}
          </Section>

          <Section title="Sell / Reduce" icon={<TrendingDown size={15} className="text-bear" />} count={data.sellReduce.length} tone="bg-bear/15 text-bear">
            {data.sellReduce.map(a => <SellCard key={a.symbol} a={a} />)}
          </Section>

          <Section title="Watch" icon={<Eye size={15} className="text-brand-light" />} count={data.watch.length} tone="bg-brand/15 text-brand-light">
            {data.watch.map((a, i) => <WatchCard key={a.symbol ?? `pf-${i}`} a={a} />)}
          </Section>

          <Section title="Hold" icon={<PauseCircle size={15} className="text-gray-400" />} count={data.hold.length} tone="bg-surface-hover text-gray-400" defaultOpen={false}>
            {data.hold.map(a => <HoldCard key={a.symbol} a={a} />)}
          </Section>

          <Section title="Not Analysed" icon={<HelpCircle size={15} className="text-gray-500" />} count={data.notAnalysed.length} tone="bg-surface-hover text-gray-500" defaultOpen={false}>
            {data.notAnalysed.map((a: NotAnalysed) => (
              <div key={a.symbol} className="rounded-lg border border-surface-border bg-surface-hover/30 p-2.5">
                <div className="flex items-center gap-2 mb-0.5">
                  <span className="text-white text-xs font-medium truncate">{a.name}</span>
                  <AssetTag assetType={a.assetType} />
                </div>
                <p className="text-2xs text-gray-500">{a.reason}</p>
              </div>
            ))}
          </Section>

          {data.buy.length + data.sellReduce.length + data.bookProfit.length + data.hold.length + data.watch.length + data.notAnalysed.length === 0 && (
            <div className="card text-center py-12 text-gray-600">
              <p className="text-sm">No holdings to analyse yet.</p>
              <p className="text-xs mt-2">Add stocks or mutual funds under Stocks / Mutual Funds to see today's actions.</p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
