import { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { TrendingUp, Minus, AlertTriangle, Loader2, HelpCircle, X } from 'lucide-react';
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
      {open && createPortal(<FundAnalysis name={fundName || symbol} label={meta.label} cls={t.cls} data={data} onClose={() => setOpen(false)} />, document.body)}
    </>
  );
}

/** The reasoning behind a fund's signal: the call, why, every factor read, and the tax view. */
function FundAnalysis({ name, label, cls, data, onClose }: { name: string; label: string; cls: string; data: AnalystAssessment; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-lg mx-4 max-h-[85vh] flex flex-col overflow-hidden"
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between gap-3 p-4 border-b border-surface-border">
          <div className="flex items-center gap-2 min-w-0">
            <span className={`text-2xs px-1.5 py-0.5 rounded border shrink-0 ${cls}`}>{label}</span>
            <h3 className="text-ink font-bold text-sm truncate">{name}</h3>
          </div>
          <button aria-label="Close" onClick={onClose} className="btn-icon text-gray-500 hover:text-ink"><X size={16} /></button>
        </div>
        <div className="p-4 space-y-4 overflow-y-auto text-xs">
          {data.nextActionReason && <p className="text-gray-300 leading-relaxed">{data.nextActionReason}</p>}
          {(data.positives?.length > 0 || data.risks?.length > 0) && (
            <div className="space-y-1">
              {data.positives?.map((r, i) => <div key={`p${i}`} className="text-gray-400 flex gap-1.5"><span className="text-bull">▸</span>{r}</div>)}
              {data.risks?.map((r, i) => <div key={`r${i}`} className="text-gray-400 flex gap-1.5"><span className="text-bear">▸</span>{r}</div>)}
            </div>
          )}
          {data.factorBreakdown?.length > 0 && (
            <div>
              <div className="text-2xs uppercase tracking-wide text-gray-600 mb-1.5">What was checked</div>
              <div className="space-y-1.5">
                {data.factorBreakdown.map((f, i) => (
                  <div key={i} className="text-gray-400">
                    <span className="text-ink font-medium">{f.name}</span>{f.reading ? <span className="font-mono"> · {f.reading}</span> : ''}{f.note ? ` — ${f.note}` : ''}
                  </div>
                ))}
              </div>
            </div>
          )}
          {data.taxImpact && <p className="text-neutral/90">{data.taxImpact}</p>}
          {data.basis && <p className="text-2xs text-gray-600">{data.basis}</p>}
          <p className="text-2xs text-gray-600">Rule-based reading of the fund's own record and your position — not investment advice.</p>
        </div>
      </div>
    </div>
  );
}
