import { useState, useEffect, useCallback } from 'react';
import { Coins, Plus, Trash2, ChevronLeft, ChevronRight, Mail } from 'lucide-react';
import { incomeApi } from '../../api/income';
import type { IncomeResponse } from '../../api/income';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

export function DividendSection() {
  const maskText = useMaskedText();
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [items, setItems] = useState<IncomeResponse[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = { description: '', amount: '', incomeDate: today() };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    try { const { data } = await incomeApi.bySource('Dividend', year); setItems(data); } catch {}
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
  // group by company (strip "Dividend — ")
  const byCompany: Record<string, number> = {};
  items.forEach(i => {
    const co = i.description.replace(/^Dividend\s*[—-]\s*/, '') || 'Other';
    byCompany[co] = (byCompany[co] ?? 0) + i.amount;
  });
  const topCompanies = Object.entries(byCompany).sort((a, b) => b[1] - a[1]).slice(0, 5);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Coins size={14} className="text-yellow-400" />
          <h3 className="font-semibold text-white text-sm">Dividends</h3>
          {items.length > 0 && <span className="text-2xs bg-yellow-400/20 text-yellow-400 px-1.5 py-0.5 rounded-full">{items.length}</span>}
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setYear(y => y - 1)} className="btn-icon"><ChevronLeft size={12} /></button>
          <span className="text-xs text-gray-400 w-12 text-center">{year}</span>
          <button onClick={() => setYear(y => y + 1)} className="btn-icon"><ChevronRight size={12} /></button>
          <button onClick={() => setOpen(o => !o)} className="btn-ghost text-xs flex items-center gap-1 ml-1"><Plus size={11} /> Add</button>
        </div>
      </div>

      <div className="flex items-center gap-1.5 mb-3 text-2xs text-gray-600">
        <Mail size={10} /> Connect Gmail above to auto-import dividend credits from registrars &amp; companies.
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <input value={f.description} onChange={e => setF(x => ({ ...x, description: e.target.value }))}
              placeholder="Company (e.g. TCS)" className="input-field text-xs" />
            <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
              placeholder="Amount ₹" className="input-field text-xs" />
          </div>
          <input type="date" value={f.incomeDate} onChange={e => setF(x => ({ ...x, incomeDate: e.target.value }))} className="input-field text-xs w-full" />
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Dividend'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {total > 0 && (
        <div className="mb-3">
          <div className="flex items-center justify-between mb-2">
            <span className="text-2xs text-gray-500">Total received in {year}</span>
            <span className="font-bold text-yellow-400 font-mono text-sm">{maskText(fmtINR(total))}</span>
          </div>
          {topCompanies.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {topCompanies.map(([co, amt]) => (
                <span key={co} className="text-2xs bg-surface-hover px-2 py-0.5 rounded-full text-gray-300">
                  {co} <span className="text-yellow-400 font-mono">{maskText(fmtINR(amt))}</span>
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No dividends recorded for {year}.</p>
        : (
          <div className="max-h-56 overflow-y-auto divide-y divide-surface-border/30">
            {items.map(d => (
              <div key={d.id} className="flex items-center justify-between py-1.5">
                <div className="min-w-0 flex-1">
                  <span className="text-white text-xs font-medium truncate">{d.description.replace(/^Dividend\s*[—-]\s*/, '')}</span>
                  <div className="text-2xs text-gray-600">{d.incomeDate}</div>
                </div>
                <div className="flex items-center gap-2 shrink-0 ml-2">
                  <span className="text-yellow-400 text-xs font-mono font-semibold">{maskText(fmtINR(d.amount))}</span>
                  <button onClick={() => del(d.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                </div>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}
