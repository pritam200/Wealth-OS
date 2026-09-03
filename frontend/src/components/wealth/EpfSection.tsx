import { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Landmark, Edit2 } from 'lucide-react';
import { trackingApi } from '../../api/tracking';
import type { EpfResponse } from '../../api/tracking';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

export function EpfSection({ onRefresh }: { onRefresh?: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<EpfResponse[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { employer: '', currentBalance: '', monthlyContribution: '', rate: '8.25', asOfDate: today() };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try { setItems((await trackingApi.listEpf()).data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.currentBalance) return;
    setSaving(true);
    try {
      const req = {
        employer: f.employer || undefined,
        currentBalance: Number(f.currentBalance),
        monthlyContribution: f.monthlyContribution ? Number(f.monthlyContribution) : undefined,
        rate: f.rate ? Number(f.rate) : undefined,
        asOfDate: f.asOfDate || undefined,
      };
      if (editId) await trackingApi.updateEpf(editId, req); else await trackingApi.addEpf(req);
      setF(blank); setOpen(false); setEditId(null); await load(); onRefresh?.();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (e: EpfResponse) => {
    setEditId(e.id); setOpen(true);
    setF({ employer: e.employer || '', currentBalance: String(e.currentBalance),
      monthlyContribution: e.monthlyContribution ? String(e.monthlyContribution) : '',
      rate: String(e.rate), asOfDate: e.asOfDate || today() });
  };

  const del = async (id: number) => { try { await trackingApi.deleteEpf(id); await load(); onRefresh?.(); } catch {} };

  const totalBal = items.reduce((s, e) => s + (e.currentBalance || 0), 0);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Landmark size={14} className="text-brand" />
          <h3 className="font-semibold text-white text-sm">EPF / Provident Fund</h3>
          {totalBal > 0 && <span className="text-xs text-gray-500 font-mono">{maskText(fmtINR(totalBal))}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-ghost text-xs flex items-center gap-1"><Plus size={11} /> Add</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <input value={f.employer} onChange={e => setF(x => ({ ...x, employer: e.target.value }))} placeholder="Employer (optional)" className="input-field text-xs" />
            <input type="number" value={f.currentBalance} onChange={e => setF(x => ({ ...x, currentBalance: e.target.value }))} placeholder="Current balance ₹ *" className="input-field text-xs" />
          </div>
          <div className="grid grid-cols-3 gap-2">
            <input type="number" value={f.monthlyContribution} onChange={e => setF(x => ({ ...x, monthlyContribution: e.target.value }))} placeholder="Monthly ₹" className="input-field text-xs" />
            <input type="number" value={f.rate} onChange={e => setF(x => ({ ...x, rate: e.target.value }))} placeholder="Rate %" className="input-field text-xs" />
            <input type="date" value={f.asOfDate} onChange={e => setF(x => ({ ...x, asOfDate: e.target.value }))} className="input-field text-xs" />
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save EPF'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No EPF added. Add your PF balance to include it in net worth and see projected growth.</p>
        : items.map(e => (
          <div key={e.id} className="flex items-center justify-between py-1.5 border-b border-surface-border/40 last:border-0">
            <div className="min-w-0">
              <span className="text-white text-xs font-medium">{e.employer || 'EPF'}</span>
              <span className="text-gray-600 text-xs ml-1.5">{maskText(`${e.rate}%`)} · {maskText(fmtINR(e.monthlyContribution))}/mo</span>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <div className="text-right">
                <div className="num text-xs text-white">{maskText(fmtINR(e.currentBalance))}</div>
                <div className="text-2xs text-gray-600">5Y → <span className="text-bull">{maskText(fmtINR(e.projected5Y))}</span></div>
              </div>
              <button onClick={() => startEdit(e)} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit EPF"><Edit2 size={11} /></button>
              <button onClick={() => del(e.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
            </div>
          </div>
        ))}
    </div>
  );
}
