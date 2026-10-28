import { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { TrendingUp, Minus, AlertTriangle, Loader2, HelpCircle } from 'lucide-react';
import { AssessmentModal } from '../shared/AssessmentModal';
import { recommendationMfApi, isInsufficient, inBadgeTurn } from '../../api/analyst';
import type { AnalystAssessment } from '../../api/analyst';

const NEXT_ACTION_META: Record<string, { label: string; tone: 'buy' | 'hold' | 'watch' | 'sell' | 'none' }> = {
  CONTINUE_SIP:            { label: 'CONTINUE SIP',  tone: 'hold' },
  INCREASE_SIP:            { label: 'INCREASE SIP',  tone: 'buy' },
  PAUSE_SIP:               { label: 'PAUSE SIP',     tone: 'watch' },
  REDIRECT_SIP:            { label: 'REDIRECT SIP',  tone: 'watch' },
  TOP_UP:                  { label: 'TOP UP',        tone: 'buy' },
  HOLD:                    { label: 'HOLD',          tone: 'hold' },
  PARTIAL_PROFIT_BOOKING:  { label: 'BOOK PROFIT',   tone: 'watch' },
  FULL_REDEMPTION:         { label: 'REDEEM',         tone: 'sell' },
  REBALANCE:               { label: 'REBALANCE',      tone: 'watch' },
  SWITCH_FUND:             { label: 'SWITCH FUND',    tone: 'sell' },
  // A loss or a weak record worth a closer look — not, on its own, a reason to sell.
  REVIEW:                  { label: 'REVIEW',         tone: 'watch' },
  // Not a recommendation — the engine declined to give one for lack of verifiable data.
  INSUFFICIENT_DATA:       { label: 'NO DATA',        tone: 'none' },
};

const TONE: Record<string, { cls: string; Icon: any }> = {
  buy:   { cls: 'text-bull border-bull/40 bg-bull/10', Icon: TrendingUp },
  hold:  { cls: 'text-gray-300 border-surface-border bg-surface-hover', Icon: Minus },
  watch: { cls: 'text-neutral border-neutral/40 bg-neutral/10', Icon: AlertTriangle },
  sell:  { cls: 'text-bear border-bear/40 bg-bear/10', Icon: AlertTriangle },
  // Dashed + unfilled + dimmer than `hold`, so "nothing to do" reads differently from
  // "we couldn't analyse this".
  none:  { cls: 'text-gray-600 border-dashed border-gray-700 bg-transparent', Icon: HelpCircle },
};

interface Props {
  symbol: string;
  fundName?: string;
  /** AMFI scheme code — lets the engine use the fund's own NAV record and category rank. */
  schemeCode?: string | null;
  investedValue: number;
  currentValue: number;
  quantity?: number;
  xirr?: number | null;
  buyDate?: string | null;
  totalMfPortfolioValue?: number;
}

// Calls the backend's dedicated MF recommendation engine (RecommendationEngine.recommendMf) —
// tax-aware (STCG/LTCG), holding-period-aware, portfolio-concentration-aware — not the stock
// engine, and not computed client-side anymore (that logic used to live entirely in this file).
export function MfTrendBadge({ symbol, fundName, schemeCode, investedValue, currentValue, quantity, xirr, buyDate, totalMfPortfolioValue }: Props) {
  const [data, setData] = useState<AnalystAssessment | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let alive = true;
    const abort = new AbortController();
    setLoading(true);
    inBadgeTurn(() => recommendationMfApi.get({
      symbol, fundName, schemeCode: schemeCode ?? undefined, buyDate: buyDate ?? undefined, xirr: xirr ?? undefined,
      investedValue, currentValue, quantity, totalMfPortfolioValue,
    }, abort.signal), abort.signal)
      .then(({ data }) => { if (alive) setData(data); })
      .catch(() => { if (alive) setFailed(true); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; abort.abort(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, schemeCode, investedValue, currentValue, xirr, buyDate, totalMfPortfolioValue]);

  if (loading) return <Loader2 size={11} className="animate-spin text-gray-600 inline" />;
  if (failed || !data) return <span className="text-2xs text-gray-700">—</span>;

  const insufficient = isInsufficient(data);
  const meta = insufficient
    ? NEXT_ACTION_META.INSUFFICIENT_DATA
    : NEXT_ACTION_META[data.nextAction] ?? NEXT_ACTION_META.HOLD;
  const t = TONE[meta.tone];
  const Icon = t.Icon;
  const why = insufficient
    ? (data.nextActionReason ?? data.risks?.join(' · ') ?? 'Not enough data to analyse this fund.')
    : [...(data.positives || []), ...(data.risks || [])].join(' · ') + (data.taxImpact ? ` · ${data.taxImpact}` : '');

  return (
    <>
      <button type="button" title={`${why}\n\nClick for the full analysis`} onClick={e => { e.stopPropagation(); setOpen(true); }}
        className={`inline-flex items-center gap-1 text-2xs px-1.5 py-0.5 rounded border cursor-pointer hover:brightness-125 ${t.cls}`}>
        <Icon size={9} /> {meta.label}
      </button>
      {open && createPortal(<AssessmentModal name={fundName || symbol} label={meta.label} cls={t.cls} data={data} onClose={() => setOpen(false)} />, document.body)}
    </>
  );
}
