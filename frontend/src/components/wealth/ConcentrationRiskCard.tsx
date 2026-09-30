import { useEffect, useState, useCallback } from 'react';
import { ShieldAlert, ChevronDown, ChevronUp, Scissors } from 'lucide-react';
import { rebalancingApi } from '../../api/rebalancing';
import type { RebalancingSuggestionsResponse, TrimSuggestion } from '../../api/rebalancing';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const pct = (n: number | null | undefined) => (n == null ? '—' : `${n.toFixed(1)}%`);

/**
 * Current-allocation concentration report — deliberately not a target-allocation rebalancer.
 * There is no user-defined target asset mix anywhere in this account, so every number here is
 * what is actually held today, and every trim idea is a read-only suggestion with its real
 * tax-lot-based tax cost, never an executed trade or an estimate.
 */
function TrimSuggestionRow({ s }: { s: TrimSuggestion }) {
  const maskText = useMaskedText();
  const [open, setOpen] = useState(false);
  const ti = s.taxImpact;

  return (
    <div className="border border-surface-border/40 rounded-lg p-2.5">
      <button onClick={() => setOpen(o => !o)} className="w-full flex items-center justify-between gap-2 text-left">
        <div className="flex items-center gap-2 min-w-0">
          <Scissors size={12} className="text-gold shrink-0" />
          <div className="min-w-0">
            <span className="text-ink text-xs font-semibold">{s.symbol}</span>
            <span className="text-gray-500 text-2xs ml-1.5">
              consider trimming ~{maskText(fmt(s.suggestedSellValue))} ({s.suggestedSellUnits.toFixed(2)} units)
            </span>
          </div>
        </div>
        {open ? <ChevronUp size={12} className="text-gray-500 shrink-0" /> : <ChevronDown size={12} className="text-gray-500 shrink-0" />}
      </button>

      {open && (
        <div className="mt-2 pt-2 border-t border-surface-border/30 space-y-1.5 text-2xs text-gray-500">
          <p>{s.reason}</p>
          {s.selectionReason && <p className="text-gray-600">{s.selectionReason}</p>}
          <p className="text-gray-600">Basis: {s.basis} — currently {pct(s.currentPercent)}.</p>

          {ti ? (
            <div className="bg-surface-hover rounded-md p-2 mt-1.5 space-y-1">
              <div className="flex justify-between"><span>Short-term gain</span><span className="num text-ink">{maskText(fmt(ti.shortTermGain))}</span></div>
              <div className="flex justify-between"><span>Long-term gain</span><span className="num text-ink">{maskText(fmt(ti.longTermGain))}</span></div>
              <div className="flex justify-between"><span>§112A exemption used</span><span className="num text-ink">{maskText(fmt(ti.exemptionUsed))}</span></div>
              <div className="flex justify-between font-semibold"><span>Tax</span><span className="num text-bear">{maskText(fmt(ti.tax))}</span></div>
              <div className="flex justify-between font-semibold"><span>Net proceeds</span><span className="num text-bull">{maskText(fmt(ti.netProceeds))}</span></div>
              {ti.deferralAdvice && <p className="text-brand pt-1">{ti.deferralAdvice}</p>}
              {ti.caveats.map((c, i) => <p key={i} className="text-gray-600 italic">{c}</p>)}
            </div>
          ) : (
            <p className="text-gray-600 italic">{s.taxImpactGap ?? 'Tax impact not available.'}</p>
          )}
        </div>
      )}
    </div>
  );
}

export function ConcentrationRiskCard() {
  const [data, setData] = useState<RebalancingSuggestionsResponse | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    try { const { data } = await rebalancingApi.getSuggestions(); setData(data); setError(false); }
    catch { setError(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  if (error) return null; // non-critical card — don't block the wealth page on this
  if (!data) return null;

  const flags = data.concentrationFlags ?? [];
  if (flags.length === 0 && data.trimSuggestions.length === 0) return null;

  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-gold"><ShieldAlert size={15} /></div>
        <div>
          <h3 className="font-bold text-ink text-sm">Concentration Risk</h3>
          <p className="text-gray-500 text-2xs">Your current allocation, flagged against portfolio-construction guidelines — not a target rebalance</p>
        </div>
      </div>

      {flags.length > 0 && (
        <div className="space-y-1.5 mb-3">
          {flags.map((f, i) => (
            <div key={i} className={`text-2xs rounded-md px-2 py-1.5 ${f.severity === 'HIGH' ? 'bg-bear/10 text-bear' : 'bg-gold/10 text-gold'}`}>
              {f.message}
            </div>
          ))}
        </div>
      )}

      {data.trimSuggestions.length > 0 && (
        <div className="space-y-2">
          {data.trimSuggestions.map((s, i) => <TrimSuggestionRow key={i} s={s} />)}
        </div>
      )}

      {data.dataGaps.length > 0 && (
        <p className="text-gray-600 text-2xs mt-3 italic">{data.dataGaps[0]}</p>
      )}
    </div>
  );
}
