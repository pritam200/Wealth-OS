import { useState, useEffect, useCallback } from 'react';
import { Home, Plus, Check, Trash2 } from 'lucide-react';
import { rentApi } from '../../api/rent';
import type { RentResponse } from '../../api/rent';
import { ledgerApi } from '../../api/ledger';
import type { CashAccount } from '../../api/ledger';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

/**
 * Rent's dedicated quick-entry flow (spec §2): "September Rent ₹31,000 ✓ Paid / October Rent
 * ₹31,000 ○ Upcoming". A schedule (due day + amount) auto-generates the next month's
 * "Upcoming" placeholder; marking one paid or entering a one-off payment both go through the
 * same recordPayment call, so a schedule-generated row and its real payment are always the
 * same record — never a separate manual entry plus a Gmail-matched duplicate.
 */
export function RentCard() {
  const maskText = useMaskedText();
  const [rows, setRows] = useState<RentResponse[]>([]);
  const [accounts, setAccounts] = useState<CashAccount[]>([]);
  const [openSchedule, setOpenSchedule] = useState(false);
  const [payingId, setPayingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);

  const scheduleBlank = { amount: '', dueDayOfMonth: '1', paidTo: '', cashAccountId: '' };
  const [sf, setSf] = useState(scheduleBlank);

  const payBlank = { paidDate: today(), referenceId: '', note: '' };
  const [pf, setPf] = useState(payBlank);

  const load = useCallback(async () => {
    try { const { data } = await rentApi.list(); setRows(data); } catch {}
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { ledgerApi.accounts().then(r => setAccounts(r.data)).catch(() => {}); }, []);

  const addSchedule = async () => {
    if (!sf.amount || !sf.dueDayOfMonth) return;
    setSaving(true);
    try {
      await rentApi.addSchedule({
        amount: Number(sf.amount), dueDayOfMonth: Number(sf.dueDayOfMonth),
        paidTo: sf.paidTo || undefined,
        cashAccountId: sf.cashAccountId ? Number(sf.cashAccountId) : undefined,
      });
      setSf(scheduleBlank); setOpenSchedule(false);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const markPaid = async (row: RentResponse) => {
    setSaving(true);
    try {
      await rentApi.recordPayment({
        month: row.month, amount: row.amount, paidDate: pf.paidDate,
        paidTo: row.paidTo || undefined, cashAccountId: row.cashAccountId || undefined,
        referenceId: pf.referenceId || undefined, note: pf.note || undefined,
      });
      setPayingId(null); setPf(payBlank);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const del = async (id: number) => {
    try { await rentApi.delete(id); await load(); } catch {}
  };

  const monthLabel = (month: string) =>
    new Date(month).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bear"><Home size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Rent</h3>
        </div>
        <button onClick={() => setOpenSchedule(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Schedule</button>
      </div>

      {openSchedule && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Monthly rent (₹) *</label>
              <input type="number" value={sf.amount} onChange={e => setSf(x => ({ ...x, amount: e.target.value }))}
                placeholder="31000" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Due day of month *</label>
              <input type="number" min={1} max={28} value={sf.dueDayOfMonth} onChange={e => setSf(x => ({ ...x, dueDayOfMonth: e.target.value }))}
                className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <input value={sf.paidTo} onChange={e => setSf(x => ({ ...x, paidTo: e.target.value }))}
              placeholder="Paid to (landlord)" className="input-field text-xs" />
            <select value={sf.cashAccountId} onChange={e => setSf(x => ({ ...x, cashAccountId: e.target.value }))} className="input-field text-xs">
              <option value="">Unspecified account</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={addSchedule} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Schedule'}</button>
            <button onClick={() => { setSf(scheduleBlank); setOpenSchedule(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {!rows.length
        ? <p className="text-gray-600 text-xs text-center py-4">No rent schedule set up yet.</p>
        : (
          <div className="space-y-0 divide-y divide-surface-border/30">
            {rows.map(r => (
              <div key={r.id}>
                <div className="flex items-center justify-between py-1.5">
                  <div className="min-w-0 flex-1">
                    <div className="text-ink text-xs font-medium truncate">{monthLabel(r.month)} Rent</div>
                    <div className="text-2xs text-gray-600 truncate">{r.paidTo || 'Unspecified payee'}{r.paidDate ? ` · Paid ${r.paidDate}` : ''}</div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-2">
                    <span className="text-ink text-xs font-mono font-semibold">{maskText(fmtINR(r.amount))}</span>
                    {r.status === 'PAID'
                      ? <span className="text-2xs font-medium text-bull px-1.5 py-0.5 rounded bg-bull/10 flex items-center gap-0.5"><Check size={10} /> Paid</span>
                      : <button onClick={() => setPayingId(payingId === r.id ? null : r.id)} className="text-2xs font-medium text-gray-400 px-1.5 py-0.5 rounded border border-surface-border hover:text-ink">○ Upcoming</button>}
                    {!r.sourceEmailId && (
                      <button aria-label="Delete" onClick={() => del(r.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                    )}
                  </div>
                </div>
                {payingId === r.id && (
                  <div className="bg-surface-hover rounded-lg p-2 mb-2 space-y-1.5 border border-surface-border">
                    <div className="grid grid-cols-2 gap-2">
                      <input type="date" value={pf.paidDate} onChange={e => setPf(x => ({ ...x, paidDate: e.target.value }))} className="input-field text-xs" />
                      <input value={pf.referenceId} onChange={e => setPf(x => ({ ...x, referenceId: e.target.value }))} placeholder="Reference ID" className="input-field text-xs" />
                    </div>
                    <button onClick={() => markPaid(r)} disabled={saving} className="btn-primary text-xs py-1.5 w-full">{saving ? 'Saving…' : 'Mark Paid'}</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )
      }
    </div>
  );
}
