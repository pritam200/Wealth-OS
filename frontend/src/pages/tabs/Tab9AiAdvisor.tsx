import { useState, useEffect, useCallback } from 'react';
import {
  Sparkles, TrendingUp, TrendingDown, Minus, ArrowUpRight,
  ArrowDownRight, RefreshCw, Loader2, Brain, Activity, ShieldCheck,
  ChevronDown, ChevronUp, HelpCircle,
} from 'lucide-react';
import { AnalystPanel } from '../../components/market/AnalystPanel';
import { RedeemedInvestments } from '../../components/wealth/RedeemedInvestments';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';
import { portfolioApi } from '../../api/portfolio';
import { recommendationApi, recommendationMfApi, isInsufficient } from '../../api/analyst';
import type { NextAction, MfNextAction } from '../../api/analyst';
import { aiApi } from '../../api/ai';
import { marketApi } from '../../api/market';
import type { PortfolioSummary, HoldingDto, AiResponse, MarketOverview } from '../../types';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

type ActionKind = NextAction | MfNextAction;

interface Action {
  holding: HoldingDto;
  kind: ActionKind;
  headline: string;
  reason: string;
  signal?: string;
  trend?: string;
  rsi?: number;
  confidenceScore?: number;
  taxImpact?: string | null;
}

const ACTION_META: Record<ActionKind, { label: string; cls: string; Icon: any }> = {
  ACCUMULATE:  { label: 'Accumulate',  cls: 'text-bull bg-bull/10 border-bull/30',       Icon: ArrowUpRight },
  CONTINUE:    { label: 'Continue',    cls: 'text-bull bg-bull/10 border-bull/30',       Icon: TrendingUp },
  BOOK_PROFIT: { label: 'Book Profit', cls: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/30', Icon: TrendingDown },
  EXIT:        { label: 'Exit',        cls: 'text-bear bg-bear/10 border-bear/30',       Icon: ArrowDownRight },
  REVIEW:      { label: 'Review',      cls: 'text-orange-400 bg-orange-400/10 border-orange-400/30', Icon: Activity },
  HOLD:        { label: 'Hold',        cls: 'text-gray-400 bg-surface-hover border-surface-border', Icon: Minus },
  // MF-specific next actions (from RecommendationEngine.recommendMf)
  CONTINUE_SIP:           { label: 'Continue SIP',  cls: 'text-bull bg-bull/10 border-bull/30',       Icon: TrendingUp },
  INCREASE_SIP:           { label: 'Increase SIP',  cls: 'text-bull bg-bull/10 border-bull/30',       Icon: ArrowUpRight },
  PAUSE_SIP:              { label: 'Pause SIP',     cls: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/30', Icon: Activity },
  PARTIAL_PROFIT_BOOKING: { label: 'Book Partial Profit', cls: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/30', Icon: TrendingDown },
  FULL_REDEMPTION:        { label: 'Redeem',        cls: 'text-bear bg-bear/10 border-bear/30',       Icon: ArrowDownRight },
  REBALANCE:              { label: 'Rebalance',     cls: 'text-orange-400 bg-orange-400/10 border-orange-400/30', Icon: Activity },
  SWITCH_FUND:            { label: 'Switch Fund',   cls: 'text-bear bg-bear/10 border-bear/30',       Icon: ArrowDownRight },
  // Not an action — the engine had nothing verifiable to work from, or the call failed.
  // Styled unlike Hold (dashed, unfilled, dimmer) so it can't be read as "nothing to do".
  INSUFFICIENT_DATA:      { label: 'No data',       cls: 'text-gray-600 bg-transparent border-dashed border-gray-700', Icon: HelpCircle },
};

const HEADLINE: Record<ActionKind, string> = {
  ACCUMULATE: 'Add on strength', CONTINUE: 'Ride the trend', BOOK_PROFIT: 'Book partial profit',
  EXIT: 'Exit / cut losses', REVIEW: 'Review position', HOLD: 'Hold',
  CONTINUE_SIP: 'Keep the SIP running', INCREASE_SIP: 'Step up the SIP', PAUSE_SIP: 'Pause new contributions',
  PARTIAL_PROFIT_BOOKING: 'Book partial profit', FULL_REDEMPTION: 'Redeem', REBALANCE: 'Rebalance allocation',
  SWITCH_FUND: 'Switch to a better fund',
  INSUFFICIENT_DATA: 'Not enough data to analyse',
};

// The single call every tab uses for BUY/HOLD/SELL + next action — same engine
// as the Research/Analyst panel and the Portfolio holdings badge, so this can
// never show a different verdict for the same stock than those tabs do.
async function decide(h: HoldingDto, totalMfPortfolioValue: number): Promise<Action> {
  if (h.symbol.endsWith('.MF')) {
    try {
      const { data } = await recommendationMfApi.get({
        symbol: h.symbol, fundName: h.name, buyDate: h.buyDate ?? undefined, xirr: h.xirr ?? undefined,
        investedValue: h.investedValue ?? 0, currentValue: h.currentValue ?? 0, quantity: h.quantity,
        totalMfPortfolioValue,
      });
      const kind = (isInsufficient(data) ? 'INSUFFICIENT_DATA' : data.nextAction) as ActionKind;
      return {
        holding: h, kind, headline: HEADLINE[kind] ?? 'Hold',
        reason: data.nextActionReason || [...(data.positives || []), ...(data.risks || [])].join(' ') || data.basis,
        confidenceScore: data.confidenceScore, taxImpact: data.taxImpact,
      };
    } catch {
      // The call failed, so we know nothing about this fund right now. Deriving a verdict
      // from P&L here ("in profit → CONTINUE_SIP") would be inventing a recommendation the
      // engine never made, and it would look identical to a real one.
      return { holding: h, kind: 'INSUFFICIENT_DATA', headline: HEADLINE.INSUFFICIENT_DATA,
        reason: 'Could not reach the recommendation service — no recommendation is shown rather than guessing one.' };
    }
  }
  try {
    const { data } = await recommendationApi.get(h.symbol, { name: h.name, pnlPercent: h.pnlPercent });
    const insufficient = isInsufficient(data);
    const kind = (insufficient ? 'INSUFFICIENT_DATA' : data.nextAction) as ActionKind;
    return {
      holding: h, kind, headline: HEADLINE[kind] ?? 'Hold',
      reason: data.nextActionReason || data.aiNarrative || data.basis,
      // Indicator context is only shown when it's real — on the INSUFFICIENT path the
      // rating is null and the trend is UNKNOWN.
      signal: insufficient ? undefined : data.rating ?? undefined,
      trend: insufficient ? undefined : data.fundamentals?.trend,
      rsi: insufficient ? undefined : data.fundamentals?.rsi ?? undefined,
      confidenceScore: data.confidenceScore,
    };
  } catch {
    return { holding: h, kind: 'INSUFFICIENT_DATA', headline: HEADLINE.INSUFFICIENT_DATA,
      reason: 'Could not reach the recommendation service — no recommendation is shown rather than guessing one.' };
  }
}

export function Tab9AiAdvisor() {
  const maskText = useMaskedText();
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [actions, setActions] = useState<Action[]>([]);
  const [loading, setLoading] = useState(true);
  const [aiReview, setAiReview] = useState<AiResponse | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [market, setMarket] = useState<MarketOverview | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data: list } = await portfolioApi.list();
      if (!list.length) { setSummary(null); setActions([]); setLoading(false); return; }
      // pick the portfolio that actually holds positions (some may be empty duplicates)
      const summaries = (await Promise.all(
        list.map(p => portfolioApi.getSummary(p.id).then(r => r.data).catch(() => null))
      )).filter(Boolean) as PortfolioSummary[];
      const sum = summaries.sort((a, b) => (b.holdings?.length ?? 0) - (a.holdings?.length ?? 0))[0];
      if (!sum) { setSummary(null); setActions([]); setLoading(false); return; }
      setSummary(sum);

      const totalMfPortfolioValue = (sum.holdings ?? [])
        .filter(h => h.symbol.endsWith('.MF'))
        .reduce((s, h) => s + (h.currentValue ?? 0), 0);
      const acts = await Promise.all((sum.holdings ?? []).map(h => decide(h, totalMfPortfolioValue)));
      // priority order for display — most urgent first
      const rank: Record<ActionKind, number> = {
        EXIT: 0, FULL_REDEMPTION: 0,
        BOOK_PROFIT: 1, PARTIAL_PROFIT_BOOKING: 1,
        REVIEW: 2, REBALANCE: 2, SWITCH_FUND: 2,
        ACCUMULATE: 3, INCREASE_SIP: 3,
        CONTINUE: 4, CONTINUE_SIP: 4,
        PAUSE_SIP: 4.5,
        HOLD: 5,
        // Least urgent — there's nothing to act on, because there was nothing to analyse.
        INSUFFICIENT_DATA: 6,
      };
      acts.sort((a, b) => rank[a.kind] - rank[b.kind]);
      setActions(acts);
    } catch {} finally { setLoading(false); }
    marketApi.getOverview().then(r => setMarket(r.data)).catch(() => {});
  }, []);

  useEffect(() => { load(); }, [load]);

  const runAiReview = async () => {
    if (!summary) return;
    setAiLoading(true);
    try { const { data } = await aiApi.portfolioReview(summary.portfolioId); setAiReview(data); }
    catch {} finally { setAiLoading(false); }
  };

  const counts = actions.reduce((m, a) => { m[a.kind] = (m[a.kind] ?? 0) + 1; return m; }, {} as Record<string, number>);
  const exitCount = (counts.EXIT ?? 0) + (counts.BOOK_PROFIT ?? 0) + (counts.FULL_REDEMPTION ?? 0) + (counts.PARTIAL_PROFIT_BOOKING ?? 0);
  const continueCount = (counts.CONTINUE ?? 0) + (counts.ACCUMULATE ?? 0) + (counts.CONTINUE_SIP ?? 0) + (counts.INCREASE_SIP ?? 0);
  const reviewCount = (counts.REVIEW ?? 0) + (counts.REBALANCE ?? 0) + (counts.SWITCH_FUND ?? 0) + (counts.PAUSE_SIP ?? 0);
  // Counted separately and never folded into an actionable bucket — these holdings have no
  // verdict at all, so rolling them into "Continue / Add" would overstate how much of the
  // portfolio the engine has actually vouched for.
  const noDataCount = counts.INSUFFICIENT_DATA ?? 0;

  return (
    <div className="space-y-5">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#6d5efc] to-[#00c2ff] flex items-center justify-center text-white shadow-lift shrink-0">
            <Brain size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">AI Advisor</h2>
            <p className="text-gray-500 text-xs">Your action for today — continue, accumulate, or exit each position</p>
          </div>
        </div>
        <button onClick={load} className="btn-secondary flex items-center gap-1.5 text-sm">
          <RefreshCw size={13} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {/* Market pulse */}
      {market && (
        <div className="flex flex-wrap gap-3">
          {[
            ['NIFTY 50', market.nifty50], ['SENSEX', market.sensex], ['BANK NIFTY', market.bankNifty],
          ].map(([label, idx]: any) => idx && (
            <div key={label} className="card py-2 px-3 flex items-center gap-3">
              <span className="text-2xs text-gray-500 uppercase tracking-wider">{label}</span>
              <span className="text-sm font-mono text-white">{idx.value?.toLocaleString('en-IN')}</span>
              <span className={`text-xs font-mono ${idx.changePercent >= 0 ? 'text-bull' : 'text-bear'}`}>
                {idx.changePercent >= 0 ? '+' : ''}{idx.changePercent?.toFixed(2)}%
              </span>
            </div>
          ))}
        </div>
      )}

      {loading ? (
        <div className="card h-40 flex items-center justify-center text-gray-500"><Loader2 className="animate-spin mr-2" size={16} /> Analysing your portfolio…</div>
      ) : !summary || !actions.length ? (
        <div className="card text-center py-12 text-gray-500">
          <Brain size={28} className="mx-auto mb-3 opacity-20" />
          <p className="text-sm">No holdings to analyse yet.</p>
          <p className="text-xs mt-1">Add stocks &amp; mutual funds, then come back for daily actions.</p>
        </div>
      ) : (
        <>
          {/* Summary strip */}
          <div className={`grid gap-3 ${noDataCount ? 'grid-cols-2 md:grid-cols-4' : 'grid-cols-3'}`}>
            <StatTile label="Continue / Add" value={String(continueCount)} Icon={TrendingUp} tone="bull" sensitive={false} />
            <StatTile label="Review" value={String(reviewCount)} Icon={Activity} tone="neutral" sensitive={false} />
            <StatTile label="Exit / Book" value={String(exitCount)} Icon={TrendingDown} tone="bear" sensitive={false} />
            {noDataCount > 0 && (
              <StatTile label="Not analysed" value={String(noDataCount)} Icon={HelpCircle} tone="neutral"
                sub="No verdict — insufficient data" sensitive={false} />
            )}
          </div>

          {/* AI review */}
          <div className="card-elevated">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2.5">
                <div className="icon-badge-brand"><Sparkles size={15} /></div>
                <h3 className="font-bold text-white text-sm">AI Portfolio Review</h3>
              </div>
              {!aiReview && (
                <button onClick={runAiReview} disabled={aiLoading} className="btn-primary text-xs py-1.5 px-3 flex items-center gap-1.5">
                  {aiLoading ? <><Loader2 size={12} className="animate-spin" /> Thinking…</> : <><Brain size={12} /> Generate</>}
                </button>
              )}
            </div>
            {aiReview ? (
              <div className="space-y-3">
                <p className="text-sm text-gray-300 leading-relaxed">{aiReview.summary}</p>
                {aiReview.risks?.length > 0 && (
                  <div>
                    <div className="text-2xs uppercase tracking-wider text-bear mb-1">Risks</div>
                    <ul className="space-y-1">{aiReview.risks.map((r, i) => <li key={i} className="text-xs text-gray-400 flex gap-1.5"><span className="text-bear">▸</span>{r}</li>)}</ul>
                  </div>
                )}
                {aiReview.opportunities?.length > 0 && (
                  <div>
                    <div className="text-2xs uppercase tracking-wider text-bull mb-1">Opportunities</div>
                    <ul className="space-y-1">{aiReview.opportunities.map((r, i) => <li key={i} className="text-xs text-gray-400 flex gap-1.5"><span className="text-bull">▸</span>{r}</li>)}</ul>
                  </div>
                )}
              </div>
            ) : (
              <p className="text-xs text-gray-600">Generate an AI-written review of your whole portfolio — strengths, risks and opportunities.</p>
            )}
          </div>

          {/* Per-holding actions */}
          <div>
            <h3 className="section-title"><div className="icon-badge-bull"><ShieldCheck size={15} /></div> Today's Actions</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {actions.map(a => {
                const meta = ACTION_META[a.kind] ?? ACTION_META.HOLD;
                const Icon = meta.Icon;
                const isStock = !(a.holding.symbol ?? '').endsWith('.MF');
                const open = expanded === a.holding.id;
                return (
                  <div key={a.holding.id} className={`card py-3 ${open ? 'md:col-span-2' : ''}`}>
                    <div className={isStock ? 'cursor-pointer' : ''} onClick={() => isStock && setExpanded(open ? null : a.holding.id)}>
                      <div className="flex items-start justify-between mb-1.5">
                        <div className="min-w-0">
                          <div className="font-mono text-white text-sm font-semibold">{a.holding.symbol.replace('.NS', '').replace('.MF', '')}</div>
                          <div className="text-2xs text-gray-600 truncate max-w-[180px]">{a.holding.name}</div>
                        </div>
                        <span className={`text-2xs font-semibold px-2 py-0.5 rounded border flex items-center gap-1 shrink-0 ${meta.cls}`}>
                          <Icon size={11} /> {meta.label}
                        </span>
                      </div>
                      <div className="flex items-center gap-3 mb-1.5 text-2xs flex-wrap">
                        <span className="text-gray-500">Value <span className="text-gray-300 font-mono">{maskText(fmtINR(a.holding.currentValue))}</span></span>
                        <span className={a.holding.pnlPercent >= 0 ? 'text-bull' : 'text-bear'}>
                          {maskText(`${a.holding.pnlPercent >= 0 ? '+' : ''}${a.holding.pnlPercent?.toFixed(1)}%`)}
                        </span>
                        {a.signal && <span className="text-gray-600">Signal {a.signal}</span>}
                        {a.trend && <span className="text-gray-600">{a.trend.replace(/_/g, ' ').toLowerCase()}</span>}
                        {a.rsi != null && <span className="text-gray-600">RSI {a.rsi.toFixed(0)}</span>}
                      </div>
                      <p className="text-xs text-gray-400 leading-snug"><span className="text-white font-medium">{a.headline}.</span> {a.reason}</p>
                      {a.taxImpact && <p className="text-2xs text-yellow-400/90 mt-1">{a.taxImpact}</p>}
                      {isStock && (
                        <div className="text-2xs text-brand mt-1.5 flex items-center gap-1">
                          {open ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
                          {open ? 'Hide full analysis' : 'Why? Full multi-factor analysis'}
                        </div>
                      )}
                    </div>
                    {open && isStock && (
                      <div className="mt-3 pt-3 border-t border-surface-border/40">
                        <AnalystPanel symbol={a.holding.symbol} name={a.holding.name} />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <p className="text-2xs text-gray-700 mt-3">Signals are generated from technical indicators and are informational, not financial advice.</p>
          </div>
        </>
      )}

      {/* Mutual Fund Reinvestment Tracker */}
      <RedeemedInvestments />
    </div>
  );
}
