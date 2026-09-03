import { useState, useEffect, useCallback } from 'react';
import { Briefcase, Plus, Edit2, Trash2, Check, X, Landmark, Shield, Coins, Home, Banknote, Heart, Globe } from 'lucide-react';
import { trackingApi } from '../../api/tracking';
import type { OtherAssetRequest, OtherAssetResponse } from '../../api/tracking';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

const ASSET_CATS = [
  { key: 'ppf',        label: 'PPF',                Icon: Landmark,  color: 'text-brand' },
  { key: 'epf',        label: 'EPF / PF',           Icon: Briefcase, color: 'text-accent' },
  { key: 'nps',        label: 'NPS',                Icon: Shield,    color: 'text-neutral' },
  { key: 'gold',       label: 'Gold / Silver',      Icon: Coins,     color: 'text-neutral' },
  { key: 'realestate', label: 'Real Estate',        Icon: Home,      color: 'text-bull' },
  { key: 'savings',    label: 'Savings / Cash',     Icon: Banknote,  color: 'text-brand' },
  { key: 'insurance',  label: 'Insurance',          Icon: Heart,     color: 'text-bear' },
  { key: 'usstocks',   label: 'US Stocks / Crypto', Icon: Globe,     color: 'text-accent' },
  { key: 'other',      label: 'Other',              Icon: Briefcase, color: 'text-gray-400' },
];

export function OtherAssetsSection({ onRefresh }: { onRefresh: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems]   = useState<OtherAssetResponse[]>([]);
  const [open, setOpen]     = useState(false);
  const [saving, setSaving] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [editVal, setEditVal] = useState('');
  const blank = { name: '', category: 'ppf', value: '', note: '', asOf: today() };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    try { const { data } = await trackingApi.listOther(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.name || !f.value) return;
    setSaving(true);
    try {
      const req: OtherAssetRequest = { name: f.name, category: f.category, value: Number(f.value), note: f.note || undefined, asOf: f.asOf || undefined };
      await trackingApi.addOther(req);
      setF(blank); setOpen(false); await load(); onRefresh();
    } catch {} finally { setSaving(false); }
  };

  const saveEdit = async (item: OtherAssetResponse) => {
    try {
      await trackingApi.updateOther(item.id, { name: item.name, category: item.category, value: Number(editVal), note: item.note || undefined, asOf: item.asOf || undefined });
      setEditId(null); await load(); onRefresh();
    } catch {}
  };

  const del = async (id: number) => {
    try { await trackingApi.deleteOther(id); await load(); onRefresh(); } catch {}
  };

  const byCategory = ASSET_CATS.map(cat => ({
    ...cat,
    entries: items.filter(i => i.category === cat.key),
    subtotal: items.filter(i => i.category === cat.key).reduce((s, x) => s + x.value, 0),
  })).filter(c => c.entries.length > 0);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-neutral"><Briefcase size={15} /></div>
          <h3 className="font-bold text-white text-sm">Other Assets</h3>
          {items.length > 0 && <span className="text-xs text-gray-500 font-mono">{maskText(fmtINR(items.reduce((s, x) => s + x.value, 0)))}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add Asset</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Category</label>
              <select value={f.category} onChange={e => setF(x => ({ ...x, category: e.target.value }))} className="input-field text-xs">
                {ASSET_CATS.map(c => <option key={c.key} value={c.key}>{c.label}</option>)}
              </select></div>
            <div><label className="stat-label block mb-1 text-2xs">Name / Description *</label>
              <input value={f.name} onChange={e => setF(x => ({ ...x, name: e.target.value }))} placeholder="SBI PPF Account" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Current Value (₹) *</label>
              <input type="number" value={f.value} onChange={e => setF(x => ({ ...x, value: e.target.value }))} placeholder="500000" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">As of Date</label>
              <input type="date" value={f.asOf} onChange={e => setF(x => ({ ...x, asOf: e.target.value }))} className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Note (optional)</label>
              <input value={f.note} onChange={e => setF(x => ({ ...x, note: e.target.value }))} placeholder="e.g. matures 2035" className="input-field text-xs" /></div>
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Asset'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">Track PPF, EPF, NPS, Gold, Real Estate, Savings, Insurance and more.</p>
        : (
          <div className="space-y-2">
            {byCategory.map(cat => {
              const Icon = cat.Icon;
              return (
                <div key={cat.key}>
                  <div className={`flex items-center gap-1.5 mb-1 ${cat.color}`}>
                    <Icon size={10} /><span className="text-xs font-semibold">{cat.label}</span>
                    <span className="font-mono text-xs ml-auto">{maskText(fmtINR(cat.subtotal))}</span>
                  </div>
                  {cat.entries.map(entry => (
                    <div key={entry.id} className="flex items-center justify-between pl-3 py-1 border-b border-surface-border/30 last:border-0">
                      <div className="min-w-0">
                        <span className="text-white text-xs">{entry.name}</span>
                        {entry.note && <span className="text-gray-600 text-xs ml-1">· {entry.note}</span>}
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        {editId === entry.id ? (
                          <>
                            <input type="number" value={editVal} onChange={e => setEditVal(e.target.value)} className="input-field text-xs w-24 py-0.5" />
                            <button onClick={() => saveEdit(entry)} className="text-bull"><Check size={12} /></button>
                            <button onClick={() => setEditId(null)} className="text-gray-500"><X size={12} /></button>
                          </>
                        ) : (
                          <>
                            <span className="num text-xs text-white">{maskText(fmtINR(entry.value))}</span>
                            <button onClick={() => { setEditId(entry.id); setEditVal(String(entry.value)); }} className="btn-icon text-gray-600 hover:text-white p-0.5"><Edit2 size={10} /></button>
                            <button onClick={() => del(entry.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                          </>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              );
            })}
          </div>
        )
      }
    </div>
  );
}
