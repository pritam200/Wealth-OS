import { useState, useEffect, useCallback } from 'react';
import { ShieldCheck, Plus, Edit2, Trash2 } from 'lucide-react';
import { insuranceApi } from '../../api/insurance';
import type { InsurancePolicyRequest, InsurancePolicyResponse, PolicyType, PremiumFrequency } from '../../api/insurance';
import { StatTile } from '../shared/StatTile';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const POLICY_TYPES: PolicyType[] = ['TERM', 'HEALTH', 'MOTOR', 'OTHER'];
const FREQUENCIES: PremiumFrequency[] = ['MONTHLY', 'QUARTERLY', 'ANNUAL'];

export function InsuranceSection({ onRefresh }: { onRefresh: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<InsurancePolicyResponse[]>([]);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = {
    policyType: 'TERM' as PolicyType, insurer: '', policyNumber: '', sumAssured: '',
    premiumAmount: '', premiumFrequency: 'ANNUAL' as PremiumFrequency,
    nextPremiumDueDate: '', startDate: '', endDate: '', notes: '',
  };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try { const { data } = await insuranceApi.list(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.insurer || !f.premiumAmount) return;
    setSaving(true);
    try {
      const req: InsurancePolicyRequest = {
        policyType: f.policyType, insurer: f.insurer, policyNumber: f.policyNumber || undefined,
        sumAssured: f.sumAssured ? Number(f.sumAssured) : undefined,
        premiumAmount: Number(f.premiumAmount), premiumFrequency: f.premiumFrequency,
        nextPremiumDueDate: f.nextPremiumDueDate || undefined,
        startDate: f.startDate || undefined, endDate: f.endDate || undefined,
        notes: f.notes || undefined,
      };
      if (editId) await insuranceApi.update(editId, req); else await insuranceApi.add(req);
      setF(blank); setOpen(false); setEditId(null);
      await load(); onRefresh();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (p: InsurancePolicyResponse) => {
    setEditId(p.id); setOpen(true);
    setF({
      policyType: p.policyType, insurer: p.insurer, policyNumber: p.policyNumber || '',
      sumAssured: p.sumAssured != null ? String(p.sumAssured) : '',
      premiumAmount: String(p.premiumAmount), premiumFrequency: p.premiumFrequency,
      nextPremiumDueDate: p.nextPremiumDueDate || '', startDate: p.startDate || '',
      endDate: p.endDate || '', notes: p.notes || '',
    });
  };

  const del = async (id: number) => {
    if (!window.confirm('Delete this policy? This cannot be undone.')) return;
    try { await insuranceApi.remove(id); await load(); onRefresh(); } catch {}
  };

  const totalSumAssured = items.reduce((s, x) => s + (x.sumAssured || 0), 0);
  const totalAnnualPremium = items.reduce((s, x) => {
    const mult = x.premiumFrequency === 'MONTHLY' ? 12 : x.premiumFrequency === 'QUARTERLY' ? 4 : 1;
    return s + x.premiumAmount * mult;
  }, 0);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-brand"><ShieldCheck size={15} /></div>
          <h3 className="font-bold text-white text-sm">Insurance Policies</h3>
          {items.length > 0 && <span className="badge-neutral">{items.length}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add Policy</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Type</label>
              <select value={f.policyType} onChange={e => setF(x => ({ ...x, policyType: e.target.value as PolicyType }))} className="input-field text-xs">
                {POLICY_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select></div>
            <div><label className="stat-label block mb-1 text-2xs">Insurer *</label>
              <input value={f.insurer} onChange={e => setF(x => ({ ...x, insurer: e.target.value }))} placeholder="HDFC Life" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Policy Number</label>
              <input value={f.policyNumber} onChange={e => setF(x => ({ ...x, policyNumber: e.target.value }))} placeholder="POL123456" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Sum Assured (₹)</label>
              <input type="number" value={f.sumAssured} onChange={e => setF(x => ({ ...x, sumAssured: e.target.value }))} placeholder="5000000" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Premium (₹) *</label>
              <input type="number" value={f.premiumAmount} onChange={e => setF(x => ({ ...x, premiumAmount: e.target.value }))} placeholder="12000" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Frequency</label>
              <select value={f.premiumFrequency} onChange={e => setF(x => ({ ...x, premiumFrequency: e.target.value as PremiumFrequency }))} className="input-field text-xs">
                {FREQUENCIES.map(fr => <option key={fr} value={fr}>{fr}</option>)}
              </select></div>
            <div><label className="stat-label block mb-1 text-2xs">Next Due Date</label>
              <input type="date" value={f.nextPremiumDueDate} onChange={e => setF(x => ({ ...x, nextPremiumDueDate: e.target.value }))} className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Start Date</label>
              <input type="date" value={f.startDate} onChange={e => setF(x => ({ ...x, startDate: e.target.value }))} className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">End Date</label>
              <input type="date" value={f.endDate} onChange={e => setF(x => ({ ...x, endDate: e.target.value }))} className="input-field text-xs" /></div>
          </div>
          <div><label className="stat-label block mb-1 text-2xs">Notes</label>
            <input value={f.notes} onChange={e => setF(x => ({ ...x, notes: e.target.value }))} placeholder="optional" className="input-field text-xs" /></div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Policy'}</button>
            <button onClick={() => { setF(blank); setOpen(false); setEditId(null); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {items.length > 0 && (
        <div className="grid grid-cols-2 gap-2 mb-3">
          <StatTile label="Total Sum Assured" value={fmtINR(totalSumAssured)} Icon={ShieldCheck} tone="brand" />
          <StatTile label="Annual Premium" value={fmtINR(totalAnnualPremium)} Icon={ShieldCheck} tone="gold" />
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No insurance policies added yet.</p>
        : items.map(p => {
          const urgency = p.daysToNextPremium != null && p.daysToNextPremium <= 15 ? 'text-bear' : 'text-gray-300';
          return (
            <div key={p.id} className="py-1.5 border-b border-surface-border/40 last:border-0">
              <div className="flex items-center justify-between">
                <div className="min-w-0">
                  <span className="text-white text-xs font-medium">{p.insurer}</span>
                  <span className="text-gray-600 text-xs ml-1.5">{p.policyType} · {maskText(fmtINR(p.premiumAmount))}/{p.premiumFrequency.toLowerCase()}</span>
                  {p.status && p.status !== 'ACTIVE' && <span className="ml-2 text-2xs bg-gray-700 text-gray-400 px-1 rounded">{p.status}</span>}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <div className="text-right">
                    {p.sumAssured != null && <div className="num text-xs text-white">{maskText(fmtINR(p.sumAssured))}</div>}
                    {p.daysToNextPremium != null && (
                      <div className={`text-2xs ${urgency}`}>{p.daysToNextPremium < 0 ? 'Overdue' : `${p.daysToNextPremium}d to premium`}</div>
                    )}
                  </div>
                  <button onClick={() => startEdit(p)} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit policy"><Edit2 size={11} /></button>
                  <button onClick={() => del(p.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                </div>
              </div>
            </div>
          );
        })
      }
    </div>
  );
}
