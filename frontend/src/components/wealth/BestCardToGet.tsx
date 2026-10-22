import { useEffect, useState } from 'react';
import { Award, Plus } from 'lucide-react';
import { cardApi } from '../../api/card';
import type { CatalogRanking, CatalogEntry } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number | null | undefined) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

/**
 * "Which card should I get": every catalog card you don't hold, ranked by what it would have
 * earned on your own recent spend, less its annual fee.
 */
export function BestCardToGet({ catalog, onAdd }: { catalog: CatalogEntry[]; onAdd: (name: string) => void }) {
  const maskText = useMaskedText();
  const [r, setR] = useState<CatalogRanking | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    cardApi.catalogRanking().then(x => setR(x.data)).catch(() => setFailed(true));
  }, [catalog.length]);

  const top = (r?.candidates ?? []).filter(c => c.netValue != null).slice(0, 3);
  const topCats = [...(r?.spendWindow.byCategory ?? [])].sort((a, b) => b.observedAmount - a.observedAmount).slice(0, 4);
  const bestFor = (name: string) => catalog.find(c => c.name === name)?.bestFor;

  return (
    <div className="card border border-brand/20">
      <div className="flex items-center gap-2 mb-1">
        <Award size={14} className="text-brand" />
        <h3 className="font-semibold text-ink text-sm">Best card to get, from your spending</h3>
      </div>
      {failed && <p className="text-2xs text-gray-500">Could not work out suggestions right now.</p>}
      {!r && !failed && <p className="text-2xs text-gray-600">Reading your spending…</p>}
      {r && (
        <>
          <p className="text-2xs text-gray-500 mb-2">
            Based on {maskText(fmtINR(r.spendWindow.observedSpend))} spent in the last {r.spendWindow.days} days
            {topCats.length > 0 && <> — mostly {topCats.map(c => c.category).join(', ')}</>}. Rewards earned minus the annual fee, projected for a year.
          </p>
          {r.spendWindow.observedSpend <= 0 ? (
            <p className="text-xs text-gray-500">No spending recorded yet, so there is nothing to rank cards against. Sync your email or add expenses.</p>
          ) : (
            <div className="space-y-2">
              {top.map((c, i) => (
                <div key={c.cardName} className="bg-surface-hover rounded-lg p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-ink text-xs font-medium">{i === 0 && <span className="text-bull mr-1">Best ·</span>}{c.cardName}</span>
                    <span className={`text-xs font-mono font-bold ${(c.netValue ?? 0) > 0 ? 'text-bull' : 'text-gray-500'}`}>
                      {maskText(fmtINR(c.netValue))}/yr
                    </span>
                  </div>
                  <p className="text-2xs text-gray-500">
                    Earns about {maskText(fmtINR(c.projectedAnnualRewards))} · fee {fmtINR(c.annualFee)}
                    {bestFor(c.cardName) ? ` · best for ${bestFor(c.cardName)}` : ''}
                  </p>
                  <button onClick={() => onAdd(c.cardName)} className="btn-ghost text-2xs flex items-center gap-1 text-brand mt-1">
                    <Plus size={10} /> Add to my cards
                  </button>
                </div>
              ))}
            </div>
          )}
          {r.caveats.filter(Boolean).map((c, i) => <p key={i} className="text-2xs text-gray-600 mt-1.5">{c}</p>)}
        </>
      )}
    </div>
  );
}
