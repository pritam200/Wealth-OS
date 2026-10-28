import { useState } from 'react';
import { PiggyBank, Loader2 } from 'lucide-react';
import { investPlanApi } from '../../api/analyst';
import type { InvestPlan, InvestRisk } from '../../api/analyst';
import { formatINR } from '../../utils/currency';
import { useMaskedText, MaskedSentence } from '../shared/Amount';

const HORIZONS = [1, 2, 3, 5, 7, 10, 15];
const RISKS: { value: InvestRisk; label: string; hint: string }[] = [
  { value: 'CONSERVATIVE', label: 'Low', hint: 'A fall of 10% would worry me' },
  { value: 'MODERATE', label: 'Medium', hint: 'I can sit through a 20% fall' },
  { value: 'AGGRESSIVE', label: 'High', hint: 'I can sit through a 35% fall' },
];

/** "I have this much saved — where could it go, and in what proportion?" Rule-based, from the backend. */
export function InvestSavingsCard() {
  const mask = useMaskedText();
  const [amount, setAmount] = useState('');
  const [years, setYears] = useState(5);
  const [risk, setRisk] = useState<InvestRisk>('MODERATE');
  const [plan, setPlan] = useState<InvestPlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const value = Number(amount.replace(/[,₹\s]/g, ''));
  const run = () => {
    if (!(value > 0)) { setError('Enter the amount you want to invest.'); return; }
    setBusy(true); setError(null);
    investPlanApi.suggest(value, years, risk)
      .then(({ data }) => setPlan(data))
      .catch(e => setError(e?.response?.data?.error ?? 'Could not work out a plan just now.'))
      .finally(() => setBusy(false));
  };

  return (
    <div className="card">
      <h3 className="section-title mb-1 flex items-center gap-2"><PiggyBank size={15} className="text-brand-light" /> Invest my savings</h3>
      <p className="text-xs text-gray-500 mb-3">Enter an amount you have saved. The split checks card dues and your emergency fund first, then fills what your portfolio is short of.</p>
      <div className="grid grid-cols-1 sm:grid-cols-4 gap-2 items-end">
        <label className="text-2xs text-gray-500">Amount (₹)
          <input inputMode="numeric" value={amount} onChange={e => setAmount(e.target.value)} onKeyDown={e => e.key === 'Enter' && run()}
            placeholder="200000" className="input-field text-xs mt-1 w-full" />
        </label>
        <label className="text-2xs text-gray-500">Needed in
          <select value={years} onChange={e => setYears(Number(e.target.value))} className="input-field text-xs mt-1 w-full">
            {HORIZONS.map(y => <option key={y} value={y}>{y === 15 ? '15+ years' : `${y} year${y === 1 ? '' : 's'}`}</option>)}
          </select>
        </label>
        <label className="text-2xs text-gray-500">Risk
          <select value={risk} onChange={e => setRisk(e.target.value as InvestRisk)} className="input-field text-xs mt-1 w-full">
            {RISKS.map(r => <option key={r.value} value={r.value}>{r.label} — {r.hint}</option>)}
          </select>
        </label>
        <button onClick={run} disabled={busy} className="btn-primary text-xs flex items-center justify-center gap-1.5 h-[34px]">
          {busy && <Loader2 size={12} className="animate-spin" />} Suggest a split
        </button>
      </div>
      {error && <p className="text-xs text-bear mt-2">{error}</p>}

      {plan && (
        <div className="mt-4 space-y-3">
          <div className="space-y-2">
            {plan.steps.map((s, i) => (
              <div key={i} className="rounded-lg border border-surface-border p-3">
                <div className="flex items-baseline justify-between gap-3">
                  <div className="text-sm text-ink font-medium">{s.label}</div>
                  <div className="text-sm num text-ink whitespace-nowrap">{mask(formatINR(s.amount))} <span className="text-2xs text-gray-500">{s.percent.toFixed(0)}%</span></div>
                </div>
                <div className="h-1.5 rounded bg-surface-hover mt-1.5 overflow-hidden"><div className="h-full bg-brand" style={{ width: `${Math.min(100, s.percent)}%` }} /></div>
                <p className="text-xs text-gray-400 mt-1.5"><MaskedSentence text={s.why} /></p>
                <p className="text-2xs text-gray-500 mt-1">
                  {s.funds.length > 0
                    ? <>Funds you already hold here: {s.funds.slice(0, 3).map(f => `${f.name}${f.return3Y != null ? ` (3Y ${f.return3Y.toFixed(1)}% a year)` : f.return1Y != null ? ` (1Y ${f.return1Y.toFixed(1)}%)` : ''}`).join('; ')}</>
                    : <>Type: {s.where}</>}
                </p>
                {s.ideas?.length > 0 && (
                  <div className="mt-2 rounded-lg bg-surface-hover/50 p-2">
                    <div className="text-2xs text-gray-400 mb-1">{s.funds.length > 0 ? 'Or a fund you don’t hold yet' : 'Funds you could start with'}:</div>
                    {s.ideas.map(i => (
                      <div key={i.schemeCode} className="text-2xs py-0.5">
                        <span className="text-ink">{i.name}</span> <span className="text-gray-500">— {i.why}</span>
                      </div>
                    ))}
                  </div>
                )}
                {s.ideasNote && <p className="text-2xs text-gray-600 mt-1">{s.ideasNote}</p>}
              </div>
            ))}
          </div>
          {plan.notes.map((n, i) => <p key={i} className="text-xs text-gray-400"><MaskedSentence text={n} /></p>)}
          <p className="text-2xs text-gray-600">{plan.disclaimer}</p>
        </div>
      )}
    </div>
  );
}
