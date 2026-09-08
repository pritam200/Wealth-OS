import { useState, useEffect, useCallback } from 'react';
import { Landmark, Plus, Edit2, Trash2, TrendingUp, Coins } from 'lucide-react';
import { trackingApi } from '../../api/tracking';
import type { FdRequest, FdResponse } from '../../api/tracking';
import { StatTile } from '../shared/StatTile';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

export function FDSection({ onRefresh }: { onRefresh: () => void }) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<FdResponse[]>([]);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { bank: '', principal: '', rate: '', compounding: 'quarterly', autoRenew: false, startDate: '', maturityDate: '' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);
  const preview = (() => {
    if (!f.principal || !f.rate || !f.startDate || !f.maturityDate) return null;
    const days = (new Date(f.maturityDate).getTime() - new Date(f.startDate).getTime()) / 86400000;
    const yrs = days / 365.25;
    const n = f.compounding === 'monthly' ? 12 : f.compounding === 'annually' ? 1 : 4;
    const r = Number(f.rate) / 100;
    const mat = Number(f.principal) * Math.pow(1 + r / n, n * yrs);
    return { mat, interest: mat - Number(f.principal) };
  })();

  const load = useCallback(async () => {
    try { const { data } = await trackingApi.listFds(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.bank || !f.principal || !f.rate) return;
    setSaving(true);
    try {
      const req: FdRequest = {
        bank: f.bank, principal: Number(f.principal), rate: Number(f.rate),
        compounding: f.compounding, autoRenew: f.autoRenew,
        startDate: f.startDate || undefined, maturityDate: f.maturityDate || undefined,
      };
      if (editId) await trackingApi.updateFd(editId, req); else await trackingApi.addFd(req);
      setF(blank); setOpen(false); setEditId(null);
      await load(); onRefresh();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (fd: FdResponse) => {
    setEditId(fd.id); setOpen(true);
    setF({ bank: fd.bank, principal: String(fd.principal), rate: String(fd.rate),
      compounding: fd.compounding, autoRenew: fd.autoRenew,
      startDate: fd.startDate || '', maturityDate: fd.maturityDate || '' });
  };

  const del = async (id: number) => {
    try { await trackingApi.deleteFd(id); await load(); onRefresh(); } catch {}
  };

  const [closingId, setClosingId] = useState<number | null>(null);
  const [closeAmt, setCloseAmt]   = useState('');

  const closeFd = async (id: number) => {
    try {
      const amt = closeAmt ? Number(closeAmt) : undefined;
      await trackingApi.closeFd(id, amt);
      setClosingId(null); setCloseAmt('');
      await load(); onRefresh();
    } catch {}
  };

  const totalPrincipal = items.reduce((s, x) => s + x.principal, 0);
  const totalInterest  = items.reduce((s, x) => s + x.interestEarned, 0);
  const totalMaturity  = items.reduce((s, x) => s + x.maturityValue, 0);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-brand"><Landmark size={15} /></div>
          <h3 className="font-bold text-white text-sm">Fixed Deposits</h3>
          {items.length > 0 && <span className="badge-neutral">{items.length}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add FD</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Bank *</label>
              <input value={f.bank} onChange={e => setF(x => ({ ...x, bank: e.target.value }))} placeholder="HDFC Bank" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Principal (₹) *</label>
              <input type="number" value={f.principal} onChange={e => setF(x => ({ ...x, principal: e.target.value }))} placeholder="100000" className="input-field text-xs" /></div>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Rate (% p.a.) *</label>
              <input type="number" value={f.rate} onChange={e => setF(x => ({ ...x, rate: e.target.value }))} placeholder="7.5" className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Compounding</label>
              <select value={f.compounding} onChange={e => setF(x => ({ ...x, compounding: e.target.value }))} className="input-field text-xs">
                <option value="quarterly">Quarterly</option>
                <option value="monthly">Monthly</option>
                <option value="annually">Annually</option>
              </select></div>
            <div><label className="stat-label block mb-1 text-2xs">Auto Renew</label>
              <label className="flex items-center gap-1.5 h-8 cursor-pointer">
                <input type="checkbox" checked={f.autoRenew} onChange={e => setF(x => ({ ...x, autoRenew: e.target.checked }))} className="accent-brand" />
                <span className="text-gray-400 text-xs">Yes</span>
              </label></div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div><label className="stat-label block mb-1 text-2xs">Start Date</label>
              <input type="date" value={f.startDate} onChange={e => setF(x => ({ ...x, startDate: e.target.value }))} className="input-field text-xs" /></div>
            <div><label className="stat-label block mb-1 text-2xs">Maturity Date</label>
              <input type="date" value={f.maturityDate} onChange={e => setF(x => ({ ...x, maturityDate: e.target.value }))} className="input-field text-xs" /></div>
          </div>
          {preview && (
            <div className="bg-brand/10 rounded px-2 py-1 text-2xs text-brand">
              Maturity: <span className="font-bold font-mono">{fmtINR(preview.mat)}</span>
              {' '}· Interest: <span className="font-bold font-mono">{fmtINR(preview.interest)}</span>
            </div>
          )}
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save FD'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {items.length > 0 && (
        <div className="grid grid-cols-3 gap-2 mb-3">
          <StatTile label="Principal" value={fmtINR(totalPrincipal)} Icon={Landmark} tone="brand" />
          <StatTile label="Interest" value={fmtINR(totalInterest)} Icon={TrendingUp} tone="bull" />
          <StatTile label="Maturity" value={fmtINR(totalMaturity)} Icon={Coins} tone="gold" />
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No FDs added yet.</p>
        : items.map(fd => {
          const isRenewed = fd.status === 'MATURED_RENEWED';
          const isClosed = fd.status === 'CLOSED' || isRenewed;
          const successor = isRenewed && fd.renewedToId != null ? items.find(x => x.id === fd.renewedToId) : undefined;
          const predecessor = fd.renewedFromId != null ? items.find(x => x.id === fd.renewedFromId) : undefined;
          const urgency = isClosed ? 'text-gray-500' : fd.daysToMaturity != null && fd.daysToMaturity <= 30 ? 'text-bear' : 'text-gray-300';
          return (
            <div key={fd.id} className={`py-1.5 border-b border-surface-border/40 last:border-0 ${isClosed ? 'opacity-70' : ''}`}>
              <div className="flex items-center justify-between">
                <div className="min-w-0">
                  <span className="text-white text-xs font-medium">{fd.bank}</span>
                  <span className="text-gray-600 text-xs ml-1.5">{maskText(`${fd.rate}%`)} {fd.compounding}{fd.autoRenew ? ' · auto-renew' : ''}</span>
                  {fd.status === 'CLOSED' && <span className="ml-2 text-2xs bg-gray-700 text-gray-400 px-1 rounded">CLOSED</span>}
                  {fd.status === 'MATURED' && <span className="ml-2 text-2xs bg-yellow-400/15 text-yellow-400 px-1 rounded">Matured — action needed</span>}
                  {isRenewed && <span className="ml-2 text-2xs bg-bull/15 text-bull px-1 rounded">Matured ✓ Renewed</span>}
                  {predecessor && <div className="text-2xs text-gray-600 mt-0.5">← Renewed from {predecessor.bank} FD of {maskText(fmtINR(predecessor.principal))}</div>}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <div className="text-right">
                    <div className="num text-xs text-white">{maskText(fmtINR(fd.principal))} → <span className="text-bull">{maskText(fmtINR(fd.actualMaturityAmount ?? fd.maturityValue))}</span></div>
                    {!isClosed && fd.daysToMaturity != null && <div className={`text-2xs ${urgency}`}>{fd.daysToMaturity < 0 ? 'Matured ✓' : `${fd.daysToMaturity}d left`}</div>}
                    {fd.status === 'CLOSED' && fd.closedDate && <div className="text-2xs text-gray-600">Closed {fd.closedDate}</div>}
                    {isRenewed && successor && <div className="text-2xs text-bull">→ Renewed into {maskText(fmtINR(successor.principal))} FD</div>}
                  </div>
                  {!isClosed && (
                    <button onClick={() => startEdit(fd)} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit FD"><Edit2 size={11} /></button>
                  )}
                  {!isClosed && (
                    <button onClick={() => setClosingId(fd.id === closingId ? null : fd.id)}
                      className="btn-icon text-gray-500 hover:text-bull p-0.5 text-2xs" title="Record FD closure in your tracker (no real bank action)">✓</button>
                  )}
                  <button onClick={() => del(fd.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                </div>
              </div>
              {closingId === fd.id && (
                <div className="mt-1.5 flex gap-2 items-center bg-surface-hover rounded p-2">
                  <input type="number" value={closeAmt} onChange={e => setCloseAmt(e.target.value)}
                    placeholder={`Actual amount (default: ${fmtINR(fd.maturityValue)})`}
                    className="input-field text-xs flex-1" />
                  <button onClick={() => closeFd(fd.id)} className="btn-primary text-xs py-1 px-3">Confirm Close</button>
                  <button onClick={() => { setClosingId(null); setCloseAmt(''); }} className="btn-ghost text-xs py-1">Cancel</button>
                </div>
              )}
            </div>
          );
        })
      }
    </div>
  );
}
