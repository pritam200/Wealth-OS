import { useState, useEffect, useCallback } from 'react';
import { Landmark, Plus, Edit2, Trash2, Calendar, Coins } from 'lucide-react';
import { trackingApi } from '../../api/tracking';
import type { RdRequest, RdResponse } from '../../api/tracking';
import { StatTile } from '../shared/StatTile';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

export function RDSection({ onRefresh }: { onRefresh: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<RdResponse[]>([]);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { bank: '', monthlyAmount: '', rate: '', startDate: '', tenureMonths: '12' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try { const { data } = await trackingApi.listRds(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.bank || !f.monthlyAmount || !f.rate) return;
    setSaving(true);
    try {
      const req: RdRequest = { bank: f.bank, monthlyAmount: Number(f.monthlyAmount), rate: Number(f.rate), startDate: f.startDate || undefined, tenureMonths: Number(f.tenureMonths) || 12 };
      if (editId) await trackingApi.updateRd(editId, req); else await trackingApi.addRd(req);
      setF(blank); setOpen(false); setEditId(null); await load(); onRefresh();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (rd: RdResponse) => {
    setEditId(rd.id); setOpen(true);
    setF({ bank: rd.bank, monthlyAmount: String(rd.monthlyAmount), rate: String(rd.rate),
      startDate: rd.startDate || '', tenureMonths: String(rd.tenureMonths) });
  };

  const del = async (id: number) => {
    try { await trackingApi.deleteRd(id); await load(); onRefresh(); } catch {}
  };

  const rdPreview = (() => {
    if (!f.monthlyAmount || !f.rate || !f.tenureMonths) return null;
    const n = Number(f.tenureMonths), r = Number(f.rate) / 100 / 12, m = Number(f.monthlyAmount);
    const corpus = r === 0 ? m * n : m * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
    return { corpus, invested: m * n };
  })();

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-brand"><Landmark size={15} /></div>
          <h3 className="font-bold text-white text-sm">Recurring Deposits</h3>
          {items.length > 0 && <span className="badge-neutral">{items.length}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add RD</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Bank *</label>
              <input value={f.bank} onChange={e => setF(x => ({ ...x, bank: e.target.value }))} placeholder="SBI" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Monthly (₹) *</label>
              <input type="number" value={f.monthlyAmount} onChange={e => setF(x => ({ ...x, monthlyAmount: e.target.value }))} placeholder="5000" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Rate (% p.a.) *</label>
              <input type="number" value={f.rate} onChange={e => setF(x => ({ ...x, rate: e.target.value }))} placeholder="7.0" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Start Date</label>
              <input type="date" value={f.startDate} onChange={e => setF(x => ({ ...x, startDate: e.target.value }))} className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Tenure (months)</label>
              <input type="number" value={f.tenureMonths} onChange={e => setF(x => ({ ...x, tenureMonths: e.target.value }))} placeholder="24" className="input-field text-xs" /></div>
          </div>
          {rdPreview && (
            <div className="bg-accent/10 rounded px-2 py-1 text-2xs text-accent">
              Maturity: <span className="font-bold font-mono">{fmtINR(rdPreview.corpus)}</span>
              {' '}· Invested: <span className="font-mono">{fmtINR(rdPreview.invested)}</span>
              {' '}· Interest: <span className="font-mono text-bull">{fmtINR(rdPreview.corpus - rdPreview.invested)}</span>
            </div>
          )}
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save RD'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {items.length > 0 && (
        <div className="grid grid-cols-2 gap-2 mb-3">
          <StatTile label="Monthly" value={fmtINR(items.reduce((s, x) => s + x.monthlyAmount, 0)) + '/mo'} Icon={Calendar} tone="brand" />
          <StatTile label="Total Corpus" value={fmtINR(items.reduce((s, x) => s + x.projectedCorpus, 0))} Icon={Coins} tone="bull" />
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No RDs added yet.</p>
        : items.map(rd => (
          <div key={rd.id} className="py-2 border-b border-surface-border/40 last:border-0">
            <div className="flex items-center justify-between mb-1">
              <div>
                <span className="text-white text-xs font-medium">{rd.bank}</span>
                <span className="text-gray-600 text-xs ml-1.5">{maskText(fmtINR(rd.monthlyAmount))}/mo · {maskText(`${rd.rate}%`)} · {rd.tenureMonths}m</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="text-right">
                  <div className="num text-xs text-white">{maskText(fmtINR(rd.totalDeposited))} → <span className="text-bull">{maskText(fmtINR(rd.projectedCorpus))}</span></div>
                  <div className="text-2xs text-gray-500">{rd.monthsElapsed}/{rd.tenureMonths} months</div>
                </div>
                <button onClick={() => startEdit(rd)} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit RD"><Edit2 size={11} /></button>
                <button onClick={() => del(rd.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
              </div>
            </div>
            <div className="h-1 bg-surface-muted rounded-full overflow-hidden">
              <div className="h-full bg-accent rounded-full" style={{ width: `${Math.min(rd.progressPercent, 100)}%` }} />
            </div>
          </div>
        ))
      }
    </div>
  );
}
