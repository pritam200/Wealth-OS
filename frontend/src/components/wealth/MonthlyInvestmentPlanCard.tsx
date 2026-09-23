import { useState, useEffect, useCallback } from 'react';
import { Target, Plus, Trash2, ChevronLeft, ChevronRight } from 'lucide-react';
import { investmentPlanApi, PLAN_INVESTMENT_TYPES } from '../../api/investmentPlan';
import type { PlannedInvestmentResponse, PlanInvestmentType } from '../../api/investmentPlan';
import { ledgerApi } from '../../api/ledger';
import type { CashAccount } from '../../api/ledger';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const STATUS_COLOR: Record<string, string> = {
  PLANNED: '#6b7280',
  PARTIAL: '#f59e0b',
  COMPLETE: '#10b981',
  OVER_INVESTED: '#3b82f6',
};

const STATUS_LABEL: Record<string, string> = {
  PLANNED: 'Planned',
  PARTIAL: 'Partial',
  COMPLETE: 'Completed',
  OVER_INVESTED: 'Over-invested',
};

/**
 * The Monthly Investment Plan (spec §3/§5/§6) — planned vs. actual, per source account,
 * for the selected calendar month. Purely a planning/tracking view: nothing here can ever
 * change net worth or create a holding — every "actual" figure comes from
 * InvestmentReconciliation rows the backend matcher already wrote against real
 * LedgerTransfer/RecurringInvestment activity.
 */
export function MonthlyInvestmentPlanCard() {
  const maskText = useMaskedText();
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [lines, setLines] = useState<PlannedInvestmentResponse[]>([]);
  const [accounts, setAccounts] = useState<CashAccount[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = { sourceAccountId: '', plannedAmount: '', investmentType: 'MUTUAL_FUND' as PlanInvestmentType, destinationRef: '' };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    try { const { data } = await investmentPlanApi.list(year, month); setLines(data); } catch {}
  }, [year, month]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { ledgerApi.accounts().then(r => setAccounts(r.data)).catch(() => {}); }, []);

  const prevMonth = () => { if (month === 1) { setYear(y => y - 1); setMonth(12); } else setMonth(m => m - 1); };
  const nextMonth = () => { if (month === 12) { setYear(y => y + 1); setMonth(1); } else setMonth(m => m + 1); };
  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  const submit = async () => {
    if (!f.plannedAmount) return;
    setSaving(true);
    try {
      await investmentPlanApi.add(year, month, {
        plannedAmount: Number(f.plannedAmount),
        investmentType: f.investmentType,
        destinationRef: f.destinationRef || undefined,
        sourceAccountId: f.sourceAccountId ? Number(f.sourceAccountId) : undefined,
      });
      setF(blank); setOpen(false);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const del = async (id: number) => {
    try { await investmentPlanApi.delete(year, month, id); await load(); } catch {}
  };

  const totalPlanned = lines.reduce((s, l) => s + l.plannedAmount, 0);
  const totalActual = lines.reduce((s, l) => s + Math.min(l.actualAmount, l.plannedAmount), 0);
  const totalRemaining = lines.reduce((s, l) => s + l.remainingAmount, 0);
  const progress = totalPlanned > 0 ? (totalActual / totalPlanned * 100) : 0;

  const accountName = (id: number | null) => accounts.find(a => a.id === id)?.name;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bull"><Target size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Monthly Investment Plan</h3>
          {lines.length > 0 && <span className="badge-bull">{lines.length}</span>}
        </div>
        <div className="flex items-center gap-2">
          <button aria-label="Previous" onClick={prevMonth} className="btn-icon"><ChevronLeft size={12} /></button>
          <span className="text-xs text-gray-400 w-28 text-center">{monthLabel}</span>
          <button aria-label="Next" onClick={nextMonth} className="btn-icon"><ChevronRight size={12} /></button>
          <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1 ml-1"><Plus size={11} /> Add</button>
        </div>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Planned amount (₹) *</label>
              <input type="number" value={f.plannedAmount} onChange={e => setF(x => ({ ...x, plannedAmount: e.target.value }))}
                placeholder="20000" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Type</label>
              <select value={f.investmentType} onChange={e => setF(x => ({ ...x, investmentType: e.target.value as PlanInvestmentType }))} className="input-field text-xs">
                {PLAN_INVESTMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">From account</label>
              <select value={f.sourceAccountId} onChange={e => setF(x => ({ ...x, sourceAccountId: e.target.value }))} className="input-field text-xs">
                <option value="">Unspecified</option>
                {accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Destination</label>
              <input value={f.destinationRef} onChange={e => setF(x => ({ ...x, destinationRef: e.target.value }))}
                placeholder="m.Stock, SBI MF…" className="input-field text-xs" />
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Add to Plan'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {totalPlanned > 0 && (
        <div className="mb-3 space-y-1.5">
          <div className="grid grid-cols-3 text-2xs text-gray-500">
            <div>Planned <span className="block text-ink font-mono font-semibold text-xs">{maskText(fmtINR(totalPlanned))}</span></div>
            <div>Invested <span className="block text-bull font-mono font-semibold text-xs">{maskText(fmtINR(totalActual))}</span></div>
            <div>Remaining <span className="block text-ink font-mono font-semibold text-xs">{maskText(fmtINR(totalRemaining))}</span></div>
          </div>
          <div className="h-1.5 bg-surface-hover rounded-full overflow-hidden">
            <div className="h-full rounded-full bg-bull" style={{ width: `${Math.min(progress, 100)}%` }} />
          </div>
          <div className="text-2xs text-gray-500 text-right">{maskText(`${progress.toFixed(1)}% complete`)}</div>
        </div>
      )}

      {!lines.length
        ? <p className="text-gray-600 text-xs text-center py-4">No investment plan for {monthLabel} yet.</p>
        : (
          <div className="space-y-2">
            {lines.map(l => {
              const pct = l.plannedAmount > 0 ? Math.min(l.actualAmount / l.plannedAmount * 100, 100) : 0;
              return (
                <div key={l.id} className="border border-surface-border/50 rounded-lg p-2">
                  <div className="flex items-center justify-between">
                    <div className="min-w-0">
                      <div className="text-xs font-medium text-ink truncate">
                        {l.destinationRef || l.investmentType.replace('_', ' ')}
                        {accountName(l.sourceAccountId) && <span className="text-gray-500"> · {accountName(l.sourceAccountId)}</span>}
                      </div>
                      <div className="text-2xs text-gray-500 font-mono">
                        {maskText(fmtINR(l.actualAmount))} / {maskText(fmtINR(l.plannedAmount))}
                      </div>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <span className="text-2xs font-medium px-1.5 py-0.5 rounded" style={{ color: STATUS_COLOR[l.status], backgroundColor: `${STATUS_COLOR[l.status]}1a` }}>
                        {STATUS_LABEL[l.status]}
                      </span>
                      {l.status === 'PLANNED' && (
                        <button aria-label="Delete" onClick={() => del(l.id)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                      )}
                    </div>
                  </div>
                  <div className="h-1 bg-surface-hover rounded-full overflow-hidden mt-1.5">
                    <div className="h-full rounded-full" style={{ width: `${pct}%`, backgroundColor: STATUS_COLOR[l.status] }} />
                  </div>
                </div>
              );
            })}
          </div>
        )
      }
    </div>
  );
}
