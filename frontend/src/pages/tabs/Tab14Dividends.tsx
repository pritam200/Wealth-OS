import { useState, useEffect, useCallback } from 'react';
import { Coins, Plus, Trash2, ChevronLeft, ChevronRight, Mail, TrendingUp, Calendar, Building2 } from 'lucide-react';
import { incomeApi } from '../../api/income';
import type { IncomeResponse } from '../../api/income';
import { useMaskedText } from '../../components/shared/Amount';
import { StatTile } from '../../components/shared/StatTile';
import { format } from 'date-fns';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

export function Tab14Dividends() {
  const maskText = useMaskedText();
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [items, setItems] = useState<IncomeResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [filter, setFilter] = useState<'all' | 'month' | 'quarter'>('all');

  const blank = { description: '', amount: '', incomeDate: today() };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    setLoading(true);
    try { const { data } = await incomeApi.bySource('Dividend', year); setItems(data); } catch {}
    setLoading(false);
  }, [year]);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.description || !f.amount) return;
    setSaving(true);
    try {
      await incomeApi.add({
        description: f.description.startsWith('Dividend') ? f.description : `Dividend — ${f.description}`,
        amount: Number(f.amount), source: 'Dividend',
        incomeDate: f.incomeDate || today(),
      });
      setF(blank); setOpen(false); await load();
    } catch {} finally { setSaving(false); }
  };

  const del = async (id: number) => { try { await incomeApi.delete(id); await load(); } catch {} };

  const total = items.reduce((s, x) => s + x.amount, 0);

  const currentMonth = now.getMonth();
  const currentQuarter = Math.floor(currentMonth / 3);
  const filtered = items.filter(d => {
    if (filter === 'all') return true;
    const m = new Date(d.incomeDate).getMonth();
    if (filter === 'month') return m === currentMonth;
    return Math.floor(m / 3) === currentQuarter;
  });

  const byCompany: Record<string, { total: number; count: number; dates: string[] }> = {};
  filtered.forEach(i => {
    const co = i.description.replace(/^Dividend\s*[—-]\s*/, '').replace(/\s*Dividend$/, '') || i.payer || 'Other';
    if (!byCompany[co]) byCompany[co] = { total: 0, count: 0, dates: [] };
    byCompany[co].total += i.amount;
    byCompany[co].count += 1;
    byCompany[co].dates.push(i.incomeDate);
  });
  const companySorted = Object.entries(byCompany).sort((a, b) => b[1].total - a[1].total);

  const byMonth: Record<string, number> = {};
  items.forEach(i => {
    const key = i.incomeDate.substring(0, 7);
    byMonth[key] = (byMonth[key] ?? 0) + i.amount;
  });
  const monthSorted = Object.entries(byMonth).sort((a, b) => a[0].localeCompare(b[0]));

  const filteredTotal = filtered.reduce((s, x) => s + x.amount, 0);
  const avgPerMonth = monthSorted.length > 0 ? total / monthSorted.length : 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-yellow-500 to-amber-600 flex items-center justify-center text-white shadow-lift shrink-0">
            <Coins size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">Dividends</h2>
            <p className="text-gray-500 text-sm">Track dividend income from your stock and mutual fund holdings</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setYear(y => y - 1)} className="btn-icon"><ChevronLeft size={14} /></button>
          <span className="text-sm text-white font-semibold w-14 text-center">{year}</span>
          <button onClick={() => setYear(y => y + 1)} className="btn-icon"><ChevronRight size={14} /></button>
          <button onClick={() => setOpen(o => !o)} className="btn-primary flex items-center gap-1.5 text-xs ml-2">
            <Plus size={13} /> Add Dividend
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="card-elevated grid grid-cols-2 lg:grid-cols-4 gap-3">
        <StatTile label={`Total in ${year}`} value={fmtINR(total)} Icon={Coins} tone="gold" />
        <StatTile label="This Period" value={fmtINR(filteredTotal)} Icon={TrendingUp} tone="brand" />
        <StatTile label="Avg per Month" value={fmtINR(avgPerMonth)} Icon={Calendar} tone="brand" />
        <StatTile label="Companies" value={String(companySorted.length)} Icon={Building2} tone="gold" sensitive={false} />
      </div>

      {/* Gmail hint */}
      <div className="flex items-center gap-1.5 text-2xs text-gray-600 px-1">
        <Mail size={10} /> Dividends are auto-imported from Gmail when connected. You can also add them manually.
      </div>

      {/* Add form */}
      {open && (
        <div className="card space-y-3">
          <h4 className="text-sm font-semibold text-white">Record a Dividend</h4>
          <div className="grid grid-cols-3 gap-3">
            <input value={f.description} onChange={e => setF(x => ({ ...x, description: e.target.value }))}
              placeholder="Company name (e.g. TCS)" className="input-field text-sm" />
            <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
              placeholder="Amount (₹)" className="input-field text-sm" />
            <input type="date" value={f.incomeDate} onChange={e => setF(x => ({ ...x, incomeDate: e.target.value }))}
              className="input-field text-sm" />
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-2 px-4">
              {saving ? 'Saving…' : 'Save Dividend'}
            </button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-2 px-4">Cancel</button>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Dividend list */}
        <div className="lg:col-span-2 card">
          <div className="flex items-center justify-between mb-4">
            <h3 className="section-title mb-0"><Coins size={15} className="text-yellow-400" /> Dividend History</h3>
            <div className="flex gap-1.5">
              {([['all', 'All'], ['quarter', 'This Quarter'], ['month', 'This Month']] as const).map(([key, label]) => (
                <button key={key} onClick={() => setFilter(key)}
                  className={`px-3 py-1.5 rounded text-xs font-medium transition-all ${
                    filter === key ? 'bg-yellow-500/20 text-yellow-400' : 'bg-surface-hover text-gray-500 hover:text-gray-300'
                  }`}>
                  {label}
                </button>
              ))}
            </div>
          </div>

          {loading ? (
            <div className="space-y-2">{[0,1,2].map(i => <div key={i} className="h-12 animate-pulse bg-surface-hover rounded-lg" />)}</div>
          ) : filtered.length === 0 ? (
            <p className="text-center text-gray-600 py-8 text-sm">No dividends recorded for this period.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th className="text-left">Company</th>
                    <th className="text-left">Date</th>
                    <th className="text-right">Amount</th>
                    <th className="text-center">Source</th>
                    <th className="w-8"></th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map(d => {
                    const company = d.description.replace(/^Dividend\s*[—-]\s*/, '').replace(/\s*Dividend$/, '') || d.payer || 'Unknown';
                    const isAuto = d.sourceEmailId != null;
                    return (
                      <tr key={d.id}>
                        <td>
                          <div className="text-white text-xs font-medium">{company}</div>
                          {d.payer && d.payer !== company && (
                            <div className="text-2xs text-gray-600">{d.payer}</div>
                          )}
                        </td>
                        <td className="text-gray-300 text-xs whitespace-nowrap">
                          {format(new Date(d.incomeDate), 'dd MMM yyyy')}
                        </td>
                        <td className="text-right">
                          <span className="text-yellow-400 font-mono font-semibold text-sm">{maskText(fmtINR(d.amount))}</span>
                        </td>
                        <td className="text-center">
                          <span className={`badge text-2xs ${isAuto ? 'badge-brand' : 'badge-neutral'}`}>
                            {isAuto ? 'Gmail' : 'Manual'}
                          </span>
                        </td>
                        <td>
                          <button onClick={() => del(d.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5">
                            <Trash2 size={11} />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Sidebar — by company & monthly breakdown */}
        <div className="space-y-4">
          <div className="card">
            <h3 className="section-title"><Building2 size={15} className="text-yellow-400" /> By Company</h3>
            {companySorted.length === 0 ? (
              <p className="text-gray-600 text-xs text-center py-4">No data yet.</p>
            ) : (
              <div className="space-y-2">
                {companySorted.map(([co, data]) => (
                  <div key={co} className="flex items-center justify-between py-1.5">
                    <div>
                      <div className="text-white text-xs font-medium">{co}</div>
                      <div className="text-2xs text-gray-600">{data.count} payment{data.count > 1 ? 's' : ''}</div>
                    </div>
                    <span className="text-yellow-400 font-mono text-xs font-semibold">{maskText(fmtINR(data.total))}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="card">
            <h3 className="section-title"><Calendar size={15} className="text-yellow-400" /> Monthly Breakdown</h3>
            {monthSorted.length === 0 ? (
              <p className="text-gray-600 text-xs text-center py-4">No data yet.</p>
            ) : (
              <div className="space-y-1.5">
                {monthSorted.map(([month, amt]) => {
                  const pct = total > 0 ? (amt / total * 100) : 0;
                  return (
                    <div key={month}>
                      <div className="flex items-center justify-between text-xs mb-0.5">
                        <span className="text-gray-400">{format(new Date(month + '-01'), 'MMM yyyy')}</span>
                        <span className="text-yellow-400 font-mono">{maskText(fmtINR(amt))}</span>
                      </div>
                      <div className="h-1.5 bg-surface-hover rounded-full overflow-hidden">
                        <div className="h-full bg-yellow-500/60 rounded-full" style={{ width: `${Math.max(pct, 2)}%` }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
