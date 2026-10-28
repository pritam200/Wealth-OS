import { useState, useEffect } from 'react';
import { ArrowUpRight, ArrowDownRight, Minus, Loader2, HelpCircle, RotateCw } from 'lucide-react';
import { recommendationApi, isInsufficient, hitRateLabel, tooShortLabel, PENDING_RETRY_MS, inBadgeTurn } from '../../api/analyst';
import type { AnalystAssessment } from '../../api/analyst';

// Same next-action vocabulary AI Advisor and the Analyst panel use — this badge used to
// show the raw `rating` (BUY/SELL/HOLD) while those two showed `nextAction`, so the exact
// same backend call could visually look like "SELL here, Book Profit there" even though
// it's one engine, one response, just two different fields of it being displayed.
const NEXT_ACTION_STYLE: Record<string, { cls: string; label: string }> = {
  ACCUMULATE:  { cls: 'text-bull bg-bull/10 border-bull/30', label: 'ACCUMULATE' },
  CONTINUE:    { cls: 'text-bull bg-bull/10 border-bull/30', label: 'CONTINUE' },
  BOOK_PROFIT: { cls: 'text-neutral bg-neutral/10 border-neutral/30', label: 'BOOK PROFIT' },
  EXIT:        { cls: 'text-bear bg-bear/10 border-bear/30', label: 'EXIT' },
  REVIEW:      { cls: 'text-neutral bg-neutral/10 border-neutral/30', label: 'REVIEW' },
  HOLD:        { cls: 'text-gray-400 bg-surface-hover border-surface-border', label: 'HOLD' },
  AVOID:       { cls: 'text-bear bg-bear/10 border-bear/30', label: 'AVOID' },
  REBALANCE:   { cls: 'text-neutral bg-neutral/10 border-neutral/30', label: 'REBALANCE' },
  NO_ACTIONABLE_SIGNAL: { cls: 'text-gray-400 bg-surface-hover border-surface-border', label: 'NO SIGNAL' },
  STALE_DATA:  { cls: 'text-amber-300 bg-transparent border-dashed border-amber-500/50', label: 'STALE' },
  // Not a recommendation — the engine declined to give one for lack of verifiable price
  // history. Deliberately styled unlike HOLD (dashed, dimmer, no fill) so "nothing to do"
  // and "we couldn't analyse this" are never mistaken for each other.
  INSUFFICIENT_DATA: { cls: 'text-gray-600 bg-transparent border-dashed border-gray-700', label: 'No data' },
};

interface Props {
  symbol: string;
  pnlPercent?: number;
  holdingValue?: number;         // this position's current value
  totalPortfolioValue?: number;  // total stock-portfolio value — enables the Portfolio Risk factor
}

// Calls the same RecommendationEngine every other tab (AI Advisor, Research) uses,
// so this badge can never disagree with what they show for the same symbol. No client-side
// cache here on purpose — a previous 2-minute local cache meant this page could keep showing
// a stale action for up to 2 minutes after AI Advisor (which always fetches fresh) had already
// moved on, which looked exactly like "not centralized" even though it was the same engine.
// The backend's own @Cacheable layer on getQuote/analyse already prevents excessive Yahoo
// calls, so there's no need for a second, page-local cache on top of it.
export function HoldingTrendBadge({ symbol, pnlPercent, holdingValue, totalPortfolioValue }: Props) {
  const [data, setData] = useState<AnalystAssessment | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  // Bumped by the retry button, and on a back-off while the history is still downloading.
  const [attempt, setAttempt] = useState(0);

  // A new symbol or position starts clean — never show the previous row's result or failure.
  useEffect(() => { setData(null); setFailed(false); setAttempt(0); }, [symbol, pnlPercent, holdingValue, totalPortfolioValue]);

  useEffect(() => {
    if (symbol.endsWith('.MF')) return; // MFs use a dedicated engine (MfTrendBadge)
    let alive = true;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const abort = new AbortController();
    setLoading(true);
    setFailed(false);
    inBadgeTurn(() => recommendationApi.get(symbol, { pnlPercent, holdingValue, totalPortfolioValue }, abort.signal), abort.signal)
      .then(({ data }) => {
        if (!alive) return;
        setData(data);
        if (data.historyPending && attempt < PENDING_RETRY_MS.length) {
          retry = setTimeout(() => { if (alive) setAttempt(a => a + 1); }, PENDING_RETRY_MS[attempt]);
        }
      })
      .catch(() => { if (alive) setFailed(true); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; abort.abort(); if (retry) clearTimeout(retry); };
  }, [symbol, pnlPercent, holdingValue, totalPortfolioValue, attempt]);

  if (symbol.endsWith('.MF')) return <span className="text-2xs text-gray-700">—</span>;
  if (loading && !data) return <Loader2 size={11} className="animate-spin text-gray-600 inline" />;
  if (failed) {
    return (
      <button type="button" onClick={() => setAttempt(a => a + 1)}
        className="inline-flex items-center gap-1 text-2xs text-gray-500 hover:text-gray-300"
        title="Couldn't load the trend for this holding — click to try again.">
        <RotateCw size={11} /> Retry
      </button>
    );
  }
  if (!data) return <span className="text-2xs text-gray-700">—</span>;

  // Still downloading and still re-asking; once the back-off runs out it falls through to "No data".
  if (data.historyPending && attempt < PENDING_RETRY_MS.length) {
    return (
      <span className="inline-flex items-center gap-1 text-2xs text-gray-500"
        title="Price history for this symbol is still downloading; the trend appears once it's in.">
        <Loader2 size={11} className="animate-spin" /> Loading
      </span>
    );
  }

  if (isInsufficient(data)) {
    const why = data.nextActionReason ?? data.risks?.join(' · ')
      ?? 'Not enough stored price history to analyse this symbol.';
    return (
      <span className="inline-flex items-center gap-1" title={why}>
        <HelpCircle size={12} className="text-gray-700" />
        <span className={`text-2xs font-medium px-1 py-0.5 rounded border ${NEXT_ACTION_STYLE.INSUFFICIENT_DATA.cls}`}>
          {NEXT_ACTION_STYLE.INSUFFICIENT_DATA.label}
        </span>
      </span>
    );
  }

  const trend = data.fundamentals?.trend;
  const up = trend?.includes('UPTREND');
  const down = trend?.includes('DOWNTREND');
  // No validated buy/sell call is the usual case, and a column of identical "NO SIGNAL" badges
  // told the user nothing. Show the descriptive trend instead; the tooltip still says it is
  // not a trade call.
  const noCall = !NEXT_ACTION_STYLE[data.nextAction] || data.nextAction === 'NO_ACTIONABLE_SIGNAL';
  const sig = noCall && trend && trend !== 'INSUFFICIENT_DATA'
    ? { cls: `${up ? 'text-bull' : down ? 'text-bear' : 'text-gray-400'} bg-transparent border-dashed border-surface-border`,
        label: trend.replace('STRONG_', 'STRONG ').replace('_', ' ') }
    : NEXT_ACTION_STYLE[data.nextAction] ?? NEXT_ACTION_STYLE.NO_ACTIONABLE_SIGNAL;
  const TrendIcon = up ? ArrowUpRight : down ? ArrowDownRight : Minus;
  const trendColor = up ? 'text-bull' : down ? 'text-bear' : 'text-gray-500';
  const title = [
    data.nextActionReason,
    data.rating === 'BUY' || data.rating === 'SELL' ? `validated ${data.rating}` : null,
    hitRateLabel(data),
    tooShortLabel(data.signalValidation),
    data.priceDate ? `prices as of ${data.priceDate}` : null,
  ].filter(Boolean).join(' · ');

  return (
    <span className="inline-flex items-center gap-1" title={title}>
      <TrendIcon size={12} className={trendColor} />
      <span className={`text-2xs font-semibold px-1 py-0.5 rounded border ${sig.cls}`}>{sig.label}</span>
    </span>
  );
}
