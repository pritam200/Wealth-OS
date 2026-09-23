import { useState, useEffect, useCallback } from 'react';
import { Repeat, Plus, Pause, Play, Trash2 } from 'lucide-react';
import { scheduledInvestmentsApi } from '../../api/scheduledInvestments';
import type { ScheduledInvestmentSummary } from '../../api/scheduledInvestments';
import { recurringInvestmentApi } from '../../api/recurringInvestment';
import type { RecurringInvestmentRequest, RecurringInvestmentType } from '../../api/recurringInvestment';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const RI_TYPES: RecurringInvestmentType[] = ['SIP', 'STOCK_SIP', 'ETF_SIP', 'BROKER_RECURRING', 'PPF', 'NPS'];

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: '#10b981', PAUSED: '#f59e0b', COMPLETED: '#6b7280',
  MATURED: '#3b82f6', CLOSED: '#6b7280', MATURED_RENEWED: '#3b82f6',
};

/**
 * The merged "Scheduled Investments" view (spec §4/§16) — RecurringInvestment
 * (SIP/PPF/NPS/STOCK_SIP/ETF_SIP/BROKER_RECURRING) and RecurringDeposit (RD) rows side by
 * side. RD itself is still managed from the FD/RD tracking area — pause/resume/delete here
 * only apply to RecurringInvestment rows (sourceKind === 'RECURRING_INVESTMENT').
 */
export function ScheduledInvestmentsCard() {
  const maskText = useMaskedText();
  const [items, setItems] = useState<ScheduledInvestmentSummary[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = { type: 'SIP' as RecurringInvestmentType, label: '', linkedSymbol: '', amount: '', dueDayOfMonth: '' };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    try { const { data } = await scheduledInvestmentsApi.listAll(); setItems(data); } catch {}
  }, []);

  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.label || !f.amount) return;
    setSaving(true);
    try {
      const day = f.dueDayOfMonth ? Number(f.dueDayOfMonth) : new Date().getDate();
      const startDate = new Date();
      startDate.setDate(Math.min(day, 28));
      const req: RecurringInvestmentRequest = {
        type: f.type, label: f.label, linkedSymbol: f.linkedSymbol || undefined,
        amount: Number(f.amount), startDate: startDate.toISOString().slice(0, 10),
      };
      await recurringInvestmentApi.add(req);
      setF(blank); setOpen(false);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const pauseResume = async (item: ScheduledInvestmentSummary) => {
    if (item.sourceKind !== 'RECURRING_INVESTMENT') return;
    try {
      await recurringInvestmentApi.update(item.sourceId, { status: item.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED' });
      await load();
    } catch {}
  };

  const del = async (item: ScheduledInvestmentSummary) => {
    if (item.sourceKind !== 'RECURRING_INVESTMENT') return;
    try { await recurringInvestmentApi.delete(item.sourceId); await load(); } catch {}
  };

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bull"><Repeat size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Scheduled Investments</h3>
          {items.length > 0 && <span className="badge-bull">{items.length}</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Label *</label>
              <input value={f.label} onChange={e => setF(x => ({ ...x, label: e.target.value }))}
                placeholder="SBI MF SIP" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Type</label>
              <select value={f.type} onChange={e => setF(x => ({ ...x, type: e.target.value as RecurringInvestmentType }))} className="input-field text-xs">
                {RI_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Amount (₹) *</label>
              <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
                placeholder="5000" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Due day of month</label>
              <input type="number" min={1} max={28} value={f.dueDayOfMonth} onChange={e => setF(x => ({ ...x, dueDayOfMonth: e.target.value }))}
                placeholder="10" className="input-field text-xs" />
            </div>
          </div>
          {(f.type === 'SIP' || f.type === 'STOCK_SIP' || f.type === 'ETF_SIP') && (
            <input value={f.linkedSymbol} onChange={e => setF(x => ({ ...x, linkedSymbol: e.target.value }))}
              placeholder="Linked symbol (e.g. HDFCFLEXICAPFUND.MF)" className="input-field text-xs w-full" />
          )}
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Add Schedule'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No scheduled investments yet.</p>
        : (
          <div className="space-y-0 divide-y divide-surface-border/30">
            {items.map(item => (
              <div key={`${item.sourceKind}-${item.sourceId}`} className="flex items-center justify-between py-1.5">
                <div className="min-w-0 flex-1">
                  <div className="text-ink text-xs font-medium truncate">{item.label}</div>
                  <div className="text-2xs text-gray-600 truncate">
                    {item.investmentType.replace('_', ' ')} · {item.frequency}
                    {item.dueDayOfMonth ? ` · ${item.dueDayOfMonth}th` : ''}
                    {item.destination ? ` · ${item.destination}` : ''}
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0 ml-2">
                  <span className="text-ink text-xs font-mono font-semibold">{maskText(fmtINR(item.amount))}</span>
                  <span className="text-2xs font-medium px-1.5 py-0.5 rounded" style={{ color: STATUS_COLOR[item.status] || '#6b7280', backgroundColor: `${STATUS_COLOR[item.status] || '#6b7280'}1a` }}>
                    {item.status}
                  </span>
                  {item.sourceKind === 'RECURRING_INVESTMENT' && (
                    <>
                      <button aria-label={item.status === 'PAUSED' ? 'Resume' : 'Pause'} onClick={() => pauseResume(item)}
                        className="btn-icon text-gray-500 hover:text-ink p-0.5">
                        {item.status === 'PAUSED' ? <Play size={11} /> : <Pause size={11} />}
                      </button>
                      <button aria-label="Delete" onClick={() => del(item)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        )
      }
    </div>
  );
}
