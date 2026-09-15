import { useEffect, useState } from 'react';
import { Calculator, Sparkles, AlertTriangle, Check } from 'lucide-react';
import { cardApi } from '../../api/card';
import type { RecommendResult } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

const QUICK_AMOUNTS = [1000, 5000, 10000, 25000];

/**
 * "Which card should I use?" — the decision this tab exists to answer, so it sits at the top
 * rather than below the card list. Answers with a ranked reward yield for a specific
 * merchant/amount, using the backend's existing recommendation logic.
 */
export function CardOptimizer() {
  const maskText = useMaskedText();
  const [categories, setCategories] = useState<string[]>([]);
  const [category, setCategory] = useState('');
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount] = useState<string>('10000');
  const [results, setResults] = useState<RecommendResult[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    cardApi.categories()
      .then(r => {
        setCategories(r.data);
        if (r.data.length > 0) setCategory(r.data[0]);
      })
      .catch(() => setCategories([]));
  }, []);

  const run = async () => {
    const amt = Number(amount);
    if (!category || !Number.isFinite(amt) || amt <= 0) {
      setError('Pick a category and enter an amount greater than zero.');
      return;
    }
    setLoading(true); setError(''); setResults(null);
    try {
      const { data } = await cardApi.recommend(category, amt, merchant.trim() || undefined);
      setResults(data);
    } catch {
      setError('Could not work out a recommendation — please try again.');
    } finally {
      setLoading(false);
    }
  };

  const eligible = (results ?? []).filter(r => r.eligible);
  const best = eligible.find(r => r.best) ?? eligible[0];
  const rest = eligible.filter(r => r !== best);
  const ineligible = (results ?? []).filter(r => !r.eligible);

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-4">
        <div className="w-9 h-9 rounded-xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Calculator size={17} />
        </div>
        <div>
          <h3 className="text-base font-bold text-ink">Which card should I use?</h3>
          <p className="text-2xs text-gray-500">Enter a purchase and see which card returns the most value</p>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 mb-3">
        <div>
          <label className="stat-label block mb-1">Category</label>
          <select value={category} onChange={e => setCategory(e.target.value)} className="input-field">
            {categories.length === 0 && <option value="">No categories</option>}
            {categories.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
        <div>
          <label className="stat-label block mb-1">Merchant <span className="text-gray-600">(optional)</span></label>
          <input value={merchant} onChange={e => setMerchant(e.target.value)}
                 placeholder="e.g. Amazon" className="input-field" />
        </div>
        <div>
          <label className="stat-label block mb-1">Amount</label>
          <input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric"
                 placeholder="10000" className="input-field font-mono tabular-nums" />
        </div>
      </div>

      <div className="flex items-center gap-1.5 flex-wrap mb-4">
        {QUICK_AMOUNTS.map(a => (
          <button key={a} onClick={() => setAmount(String(a))}
                  className={`text-2xs font-mono tabular-nums px-2 py-1 rounded-lg border transition-colors ${
                    amount === String(a)
                      ? 'bg-brand/15 text-brand-light border-brand/30'
                      : 'bg-surface-hover text-gray-400 border-surface-border hover:text-gray-200'}`}>
            {fmt(a)}
          </button>
        ))}
        <button onClick={run} disabled={loading} className="btn-primary text-xs ml-auto">
          {loading ? 'Working…' : <><Sparkles size={13} /> Find best card</>}
        </button>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-2xs text-bear mb-3">
          <AlertTriangle size={12} /> {error}
        </div>
      )}

      {results && eligible.length === 0 && (
        <p className="text-xs text-gray-500 py-3">
          None of your cards earn rewards on this category. Add a card under the list below, or
          check the catalogue for one that does.
        </p>
      )}

      {best && (
        <>
          {/* The answer, stated once and prominently. */}
          <div className="rounded-xl border border-bull/25 bg-bull/5 p-3.5 mb-2.5">
            <div className="flex items-start justify-between gap-3 flex-wrap">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1 flex-wrap">
                  <span className="pill-bull"><Check size={11} /> Use this</span>
                  <span className="text-ink font-semibold text-sm truncate">{best.cardName}</span>
                  {best.fromCatalog && <span className="pill-muted">Not yet added</span>}
                </div>
                <p className="text-2xs text-gray-400">{best.reason}</p>
              </div>
              <div className="text-right shrink-0">
                <div className="stat-label mb-0.5">You earn</div>
                <div className="text-xl font-mono tabular-nums font-bold text-bull">
                  {maskText(fmt(best.expectedReward))}
                </div>
                <div className="text-2xs text-gray-500 font-mono tabular-nums">
                  {best.rewardRate.toFixed(2)}% back
                </div>
              </div>
            </div>
          </div>

          {rest.length > 0 && (
            <div className="space-y-1.5">
              {rest.map((r, i) => {
                const lost = best.expectedReward - r.expectedReward;
                return (
                  <div key={`${r.cardName}-${i}`}
                       className="flex items-center justify-between gap-3 rounded-lg border border-surface-border bg-surface-hover/40 px-3 py-2">
                    <div className="min-w-0">
                      <span className="text-xs text-gray-300 truncate">{r.cardName}</span>
                      <span className="text-2xs text-gray-600 ml-2 font-mono tabular-nums">{r.rewardRate.toFixed(2)}%</span>
                    </div>
                    <div className="text-right shrink-0">
                      <span className="text-xs font-mono tabular-nums text-gray-300">{maskText(fmt(r.expectedReward))}</span>
                      {lost > 0 && (
                        <span className="text-2xs text-bear ml-2 font-mono tabular-nums">−{maskText(fmt(lost))}</span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {ineligible.length > 0 && (
            <p className="text-2xs text-gray-600 mt-2.5">
              {ineligible.length} card{ineligible.length === 1 ? '' : 's'} excluded:{' '}
              {ineligible.map(r => r.ineligibilityNote || r.cardName).join('; ')}
            </p>
          )}
        </>
      )}
    </div>
  );
}
