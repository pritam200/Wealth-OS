import { useState } from 'react';
import { Shield, TrendingUp, AlertTriangle } from 'lucide-react';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const SCENARIOS = [
  { label: 'Conservative', rate: 8,  color: 'text-brand',  border: 'border-brand/30',  bg: 'bg-brand/5',  Icon: Shield },
  { label: 'Base',         rate: 12, color: 'text-neutral', border: 'border-neutral/30',bg: 'bg-neutral/5', Icon: TrendingUp },
  { label: 'Aggressive',   rate: 16, color: 'text-bull',    border: 'border-bull/30',   bg: 'bg-bull/5',   Icon: AlertTriangle },
];

export function WealthCalculator() {
  const [invested, setInvested] = useState('500000');
  const [monthly,  setMonthly]  = useState('10000');
  const [years,    setYears]    = useState('10');
  const inv = Number(invested) || 0, mon = Number(monthly) || 0, yrs = Number(years) || 10;
  const compound = (rate: number) => {
    const r = rate / 100 / 12, n = yrs * 12;
    return r === 0 ? inv + mon * n : inv * Math.pow(1 + r, n) + mon * ((Math.pow(1 + r, n) - 1) / r);
  };
  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><TrendingUp size={15} /></div>
        <h3 className="font-bold text-white text-sm">Wealth Growth Calculator</h3>
      </div>
      <div className="space-y-2 mb-4">
        <div><label className="stat-label block mb-1 text-2xs">Lump Sum / Current Investment (₹)</label>
          <input type="number" value={invested} onChange={e => setInvested(e.target.value)} className="input-field" /></div>
        <div><label className="stat-label block mb-1 text-2xs">Monthly SIP / Savings (₹)</label>
          <input type="number" value={monthly} onChange={e => setMonthly(e.target.value)} className="input-field" /></div>
        <div>
          <label className="stat-label block mb-1 text-2xs">Horizon: <span className="text-white font-semibold">{yrs} years</span></label>
          <input type="range" min={1} max={30} value={years} onChange={e => setYears(e.target.value)} className="w-full accent-brand" />
          <div className="flex justify-between text-2xs text-gray-600 mt-0.5"><span>1Y</span><span>15Y</span><span>30Y</span></div>
        </div>
      </div>
      <div className="space-y-2">
        {SCENARIOS.map(s => {
          const corpus = compound(s.rate), totalIn = inv + mon * yrs * 12;
          const Icon = s.Icon;
          return (
            <div key={s.label} className={`rounded-lg p-2.5 border ${s.border} ${s.bg}`}>
              <div className={`flex items-center gap-1.5 mb-1.5 ${s.color}`}>
                <Icon size={11} /><span className="font-semibold text-xs">{s.label} ({s.rate}%)</span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div><div className="text-gray-500 text-2xs">Projected Corpus</div><div className={`font-bold ${s.color} text-sm`}>{fmtINR(corpus)}</div></div>
                <div><div className="text-gray-500 text-2xs">Wealth Created</div><div className="font-medium text-white text-sm">{fmtINR(corpus - totalIn)}</div></div>
              </div>
            </div>
          );
        })}
      </div>
      <p className="text-2xs text-gray-700 mt-2">Total invested: {fmtINR(inv + mon * yrs * 12)} · Illustrative only.</p>
    </div>
  );
}
