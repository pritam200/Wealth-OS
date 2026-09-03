import { useState, useEffect, useCallback } from 'react';
import { CreditCard, Plus, Edit2, Trash2, Calendar } from 'lucide-react';
import { trackingApi } from '../../api/tracking';
import type { LoanRequest, LoanResponse } from '../../api/tracking';
import { StatTile } from '../shared/StatTile';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const LOAN_TYPES = ['Home Loan','Car Loan','Personal Loan','Education Loan','Business Loan','Gold Loan','Other'];

export function LoanSection({ onRefresh }: { onRefresh: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<LoanResponse[]>([]);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { name: '', type: 'Home Loan', emi: '', outstanding: '', rate: '', remainingMonths: '' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try { const { data } = await trackingApi.listLoans(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.name || !f.emi) return;
    setSaving(true);
    try {
      const req: LoanRequest = { name: f.name, type: f.type, emi: Number(f.emi), outstanding: Number(f.outstanding) || 0, rate: Number(f.rate) || 0, remainingMonths: Number(f.remainingMonths) || 0 };
      if (editId) await trackingApi.updateLoan(editId, req); else await trackingApi.addLoan(req);
      setF(blank); setOpen(false); setEditId(null); await load(); onRefresh();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (l: LoanResponse) => {
    setEditId(l.id); setOpen(true);
    setF({ name: l.name, type: l.type, emi: String(l.emi), outstanding: String(l.outstanding),
      rate: String(l.rate), remainingMonths: String(l.remainingMonths) });
  };

  const del = async (id: number) => {
    try { await trackingApi.deleteLoan(id); await load(); onRefresh(); } catch {}
  };

  const loanPreview = f.emi && f.remainingMonths ? { total: Number(f.emi) * Number(f.remainingMonths), interest: Math.max(0, Number(f.emi) * Number(f.remainingMonths) - Number(f.outstanding || 0)) } : null;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bear"><CreditCard size={15} /></div>
          <h3 className="font-bold text-white text-sm">Loans &amp; EMIs</h3>
          {items.length > 0 && <span className="text-xs text-bear font-mono">{maskText(fmtINR(items.reduce((s, x) => s + x.emi, 0)))}/mo</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add Loan</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Loan Name *</label>
              <input value={f.name} onChange={e => setF(x => ({ ...x, name: e.target.value }))} placeholder="Home Loan – HDFC" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Type</label>
              <select value={f.type} onChange={e => setF(x => ({ ...x, type: e.target.value }))} className="input-field text-xs">
                {LOAN_TYPES.map(t => <option key={t}>{t}</option>)}
              </select></div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Monthly EMI (₹) *</label>
              <input type="number" value={f.emi} onChange={e => setF(x => ({ ...x, emi: e.target.value }))} placeholder="25000" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Outstanding Balance (₹)</label>
              <input type="number" value={f.outstanding} onChange={e => setF(x => ({ ...x, outstanding: e.target.value }))} placeholder="2500000" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Interest Rate (% p.a.)</label>
              <input type="number" value={f.rate} onChange={e => setF(x => ({ ...x, rate: e.target.value }))} placeholder="8.5" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Remaining Months</label>
              <input type="number" value={f.remainingMonths} onChange={e => setF(x => ({ ...x, remainingMonths: e.target.value }))} placeholder="200" className="input-field text-xs" /></div>
          </div>
          {loanPreview && (
            <div className="bg-bear/10 rounded px-2 py-1 text-2xs text-bear">
              Total payable: <span className="font-bold font-mono">{fmtINR(loanPreview.total)}</span>
              {' '}· Interest burden: <span className="font-mono">{fmtINR(loanPreview.interest)}</span>
            </div>
          )}
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Loan'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {items.length > 0 && (
        <div className="grid grid-cols-2 gap-2 mb-3">
          <StatTile label="Monthly EMI" value={fmtINR(items.reduce((s, x) => s + x.emi, 0)) + '/mo'} Icon={Calendar} tone="bear" />
          <StatTile label="Outstanding" value={fmtINR(items.reduce((s, x) => s + x.outstanding, 0))} Icon={CreditCard} tone="neutral" />
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No loans tracked.</p>
        : items.map(l => (
          <div key={l.id} className="py-2 border-b border-surface-border/40 last:border-0">
            <div className="flex items-center justify-between mb-1">
              <div>
                <div className="flex items-center gap-1.5">
                  <span className="text-white text-xs font-medium">{l.name}</span>
                  <span className="text-2xs text-gray-600 bg-surface-hover px-1 rounded">{l.type}</span>
                </div>
                <div className="text-2xs text-gray-600">{maskText(`${l.rate}%`)} p.a. · {l.remainingMonths}m left · Interest: {maskText(fmtINR(l.totalInterestPayable))}</div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <div className="text-right">
                  <div className="text-bear num text-xs">EMI {maskText(fmtINR(l.emi))}/mo</div>
                  <div className="text-gray-500 num text-2xs">Bal {maskText(fmtINR(l.outstanding))}</div>
                </div>
                <button onClick={() => startEdit(l)} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit loan"><Edit2 size={11} /></button>
                <button onClick={() => del(l.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
              </div>
            </div>
            {l.outstanding > 0 && l.remainingMonths > 0 && (
              <div className="h-1 bg-surface-muted rounded-full overflow-hidden">
                <div className="h-full bg-bear rounded-full" style={{ width: `${Math.min(l.repaidPercent, 100)}%` }} />
              </div>
            )}
          </div>
        ))
      }
    </div>
  );
}
