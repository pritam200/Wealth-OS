import { useState, useEffect } from 'react';
import { TrendingUp, Minus, AlertTriangle, Loader2, HelpCircle } from 'lucide-react';
import { recommendationMfApi, isInsufficient } from '../../api/analyst';
import type { AnalystAssessment } from '../../api/analyst';

const NEXT_ACTION_META: Record<string, { label: string; tone: 'buy' | 'hold' | 'watch' | 'sell' | 'none' }> = {
  CONTINUE_SIP:            { label: 'CONTINUE SIP',  tone: 'hold' },
  INCREASE_SIP:            { label: 'INCREASE SIP',  tone: 'buy' },
  PAUSE_SIP:               { label: 'PAUSE SIP',     tone: 'watch' },
  HOLD:                    { label: 'HOLD',          tone: 'hold' },
  PARTIAL_PROFIT_BOOKING:  { label: 'BOOK PROFIT',   tone: 'watch' },
  FULL_REDEMPTION:         { label: 'REDEEM',         tone: 'sell' },
  REBALANCE:               { label: 'REBALANCE',      tone: 'watch' },
  SWITCH_FUND:             { label: 'SWITCH FUND',    tone: 'sell' },
  // Not a recommendation — the engine declined to give one for lack of verifiable data.
  INSUFFICIENT_DATA:       { label: 'NO DATA',        tone: 'none' },
};

const TONE: Record<string, { cls: string; Icon: any }> = {
  buy:   { cls: 'text-bull border-bull/40 bg-bull/10', Icon: TrendingUp },
  hold:  { cls: 'text-gray-300 border-surface-border bg-surface-hover', Icon: Minus },
  watch: { cls: 'text-yellow-400 border-yellow-400/40 bg-yellow-400/10', Icon: AlertTriangle },
  sell:  { cls: 'text-bear border-bear/40 bg-bear/10', Icon: AlertTriangle },
  // Dashed + unfilled + dimmer than `hold`, so "nothing to do" reads differently from
  // "we couldn't analyse this".
  none:  { cls: 'text-gray-600 border-dashed border-gray-700 bg-transparent', Icon: HelpCircle },
};

interface Props {
  symbol: string;
  fundName?: string;
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
export function MfTrendBadge({ symbol, fundName, investedValue, currentValue, quantity, xirr, buyDate, totalMfPortfolioValue }: Props) {
  const [data, setData] = useState<AnalystAssessment | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    recommendationMfApi.get({
      symbol, fundName, buyDate: buyDate ?? undefined, xirr: xirr ?? undefined,
      investedValue, currentValue, quantity, totalMfPortfolioValue,
    })
      .then(({ data }) => { if (alive) setData(data); })
      .catch(() => { if (alive) setFailed(true); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, investedValue, currentValue, xirr, buyDate, totalMfPortfolioValue]);

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
    <span title={why} className={`inline-flex items-center gap-1 text-2xs px-1.5 py-0.5 rounded border ${t.cls}`}>
      <Icon size={9} /> {meta.label}
    </span>
  );
}
