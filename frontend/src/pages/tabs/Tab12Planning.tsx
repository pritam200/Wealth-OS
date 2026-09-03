import { useState, useEffect, useCallback } from 'react';
import {
  Bell, Target, Receipt, Plus, Trash2, CheckCircle, AlertTriangle, CalendarClock,
  TrendingUp, Coins, Landmark, Wallet,
} from 'lucide-react';
import { reminderApi, goalApi, taxApi, GOAL_CATEGORIES } from '../../api/planning';
import type { Reminder, GoalResponse, TaxResponse } from '../../api/planning';
import { scheduledInvestmentApi } from '../../api/scheduledInvestment';
import type { RecurringInvestment } from '../../api/scheduledInvestment';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

export function Tab12Planning() {
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#ffb454] to-[#ff8a5b] flex items-center justify-center text-white shadow-lift shrink-0">
          <Target size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Financial Planning</h2>
          <p className="text-gray-500 text-xs">Reminders, goals and tax — the forward-looking side of your money</p>
        </div>
      </div>
      <RemindersPanel />
      <ScheduledInvestmentsSection />
      <GoalsSection />
      <TaxSummary />
    </div>
  );
}

/* ── Scheduled Investments (SIP / PPF / NPS) ── */
const INSTALLMENT_DOT: Record<string, string> = { COMPLETED: 'bg-bull', MISSED: 'bg-bear', UPCOMING: 'bg-gray-500' };

function ScheduledInvestmentsSection() {
  const maskText = useMaskedText();
  const [items, setItems] = useState<RecurringInvestment[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { type: 'SIP' as const, label: '', linkedSymbol: '', amount: '', startDate: today(), tenureMonths: '' };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => {
    try { setItems((await scheduledInvestmentApi.list()).data); } catch {} finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.label || !f.amount) return;
    setSaving(true);
    try {
      await scheduledInvestmentApi.add({
        type: f.type, label: f.label, linkedSymbol: f.linkedSymbol || undefined,
        amount: Number(f.amount), startDate: f.startDate || undefined,
        tenureMonths: f.tenureMonths ? Number(f.tenureMonths) : undefined,
      });
      setF(blank); setOpen(false); await load();
    } catch {} finally { setSaving(false); }
  };

  const monthlyTotal = items.filter(i => i.status === 'ACTIVE').reduce((s, i) => s + (i.amount || 0), 0);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-brand"><CalendarClock size={15} /></div>
          <h3 className="font-bold text-white text-sm">Scheduled Investments</h3>
          {items.length > 0 && <span className="badge-neutral">{maskText(fmtINR(monthlyTotal))}/mo</span>}
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <select value={f.type} onChange={e => setF(x => ({ ...x, type: e.target.value as any }))} className="input-field text-xs">
              <option value="SIP">SIP</option><option value="PPF">PPF</option><option value="NPS">NPS</option>
            </select>
            <input value={f.label} onChange={e => setF(x => ({ ...x, label: e.target.value }))} placeholder="Label (fund/account name)" className="input-field text-xs" />
          </div>
          {f.type === 'SIP' && (
            <input value={f.linkedSymbol} onChange={e => setF(x => ({ ...x, linkedSymbol: e.target.value }))} placeholder="Linked MF symbol (e.g. HDFCFLEXICA.MF) — optional, enables completed/missed tracking" className="input-field text-xs w-full" />
          )}
          <div className="grid grid-cols-3 gap-2">
            <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))} placeholder="Amount ₹/mo" className="input-field text-xs" />
            <input type="date" value={f.startDate} onChange={e => setF(x => ({ ...x, startDate: e.target.value }))} className="input-field text-xs" />
            <input type="number" value={f.tenureMonths} onChange={e => setF(x => ({ ...x, tenureMonths: e.target.value }))} placeholder="Tenure (months, optional)" className="input-field text-xs" />
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="h-10 animate-pulse bg-surface-hover rounded" />
      ) : !items.length ? (
        <p className="text-gray-600 text-xs text-center py-4">No recurring investments tracked yet. Add a SIP, PPF, or NPS contribution.</p>
      ) : (
        <div className="space-y-2">
          {items.map(i => (
            <div key={i.id} className="bg-surface-hover rounded-lg p-2.5">
              <div className="flex items-center justify-between mb-1.5">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-2xs bg-brand/15 text-brand px-1.5 py-0.5 rounded">{i.type}</span>
                  <span className="text-white text-xs font-medium truncate max-w-[160px]">{i.label}</span>
                  <span className="text-2xs text-gray-500 font-mono">{maskText(fmtINR(i.amount))}/mo</span>
                </div>
                <button onClick={async () => { await scheduledInvestmentApi.delete(i.id); load(); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
              </div>
              <div className="flex items-center gap-1.5 flex-wrap">
                {i.installments.slice(-8).map((inst, idx) => (
                  <span key={idx} title={`${inst.dueDate} — ${inst.status}`} className={`w-2 h-2 rounded-full ${INSTALLMENT_DOT[inst.status]}`} />
                ))}
                {i.type === 'SIP' && i.linkedSymbol && (
                  <span className="text-2xs text-gray-600 ml-1">{i.completedCount} completed{i.missedCount > 0 ? `, ${i.missedCount} missed` : ''}</span>
                )}
                {(i.type !== 'SIP' || !i.linkedSymbol) && i.installments[0] && (
                  <span className="text-2xs text-gray-600 ml-1">next due {i.installments[0].dueDate}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/* ── Reminders ── */
const SEV_STYLE: Record<string, string> = {
  OVERDUE: 'border-bear/40 bg-bear/5', DUE_SOON: 'border-yellow-400/40 bg-yellow-400/5', UPCOMING: 'border-surface-border bg-surface-hover',
};
const SEV_DOT: Record<string, string> = { OVERDUE: 'bg-bear', DUE_SOON: 'bg-yellow-400', UPCOMING: 'bg-gray-500' };

export function RemindersPanel() {
  const maskText = useMaskedText();
  const [items, setItems] = useState<Reminder[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => { reminderApi.list().then(r => setItems(r.data)).catch(() => {}).finally(() => setLoading(false)); }, []);

  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Bell size={15} /></div>
        <h3 className="font-bold text-white text-sm">Reminders</h3>
        {items.length > 0 && <span className="badge-neutral">{items.length}</span>}
      </div>
      {loading
        ? <div className="h-10 animate-pulse bg-surface-hover rounded" />
        : !items.length
          ? <p className="text-gray-600 text-xs text-center py-4">Nothing due soon. FD maturities, RD installments and card bills will appear here automatically.</p>
          : (
            <div className="space-y-2">
              {items.map((r, i) => (
                <div key={i} className={`flex items-center justify-between rounded-lg border p-2.5 ${SEV_STYLE[r.severity]}`}>
                  <div className="flex items-center gap-2 min-w-0">
                    <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${SEV_DOT[r.severity]}`} />
                    <div className="min-w-0">
                      <div className="text-white text-xs font-medium truncate">{r.title}</div>
                      <div className="text-2xs text-gray-500">{r.subtitle}</div>
                    </div>
                  </div>
                  <div className="text-right shrink-0 ml-2">
                    {r.amount > 0 && <div className="text-xs font-mono text-white">{maskText(fmtINR(r.amount))}</div>}
                    <div className={`text-2xs ${r.daysUntil < 0 ? 'text-bear' : r.daysUntil <= 7 ? 'text-yellow-400' : 'text-gray-500'}`}>
                      {r.daysUntil < 0 ? `${Math.abs(r.daysUntil)}d overdue` : r.daysUntil === 0 ? 'due today' : `in ${r.daysUntil}d`}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
    </div>
  );
}

/* ── Goals ── */
const today = () => new Date().toISOString().slice(0, 10);
function GoalsSection() {
  const maskText = useMaskedText();
  const [goals, setGoals] = useState<GoalResponse[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const blank = { name: '', category: 'Retirement', targetAmount: '', currentSaved: '', monthlyContribution: '', expectedReturn: '10', targetDate: '' };
  const [f, setF] = useState(blank);

  const load = useCallback(async () => { try { setGoals((await goalApi.list()).data); } catch {} }, []);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (!f.name || !f.targetAmount) return;
    setSaving(true);
    try {
      await goalApi.add({
        name: f.name, category: f.category, targetAmount: Number(f.targetAmount),
        currentSaved: f.currentSaved ? Number(f.currentSaved) : 0,
        monthlyContribution: f.monthlyContribution ? Number(f.monthlyContribution) : 0,
        expectedReturn: f.expectedReturn ? Number(f.expectedReturn) : 10,
        targetDate: f.targetDate || undefined,
      });
      setF(blank); setOpen(false); await load();
    } catch {} finally { setSaving(false); }
  };

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-gold"><Target size={15} /></div>
          <h3 className="font-bold text-white text-sm">Financial Goals</h3>
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add Goal</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <input value={f.name} onChange={e => setF(x => ({ ...x, name: e.target.value }))} placeholder="Goal name (e.g. Retirement)" className="input-field text-xs" />
            <select value={f.category} onChange={e => setF(x => ({ ...x, category: e.target.value }))} className="input-field text-xs">
              {GOAL_CATEGORIES.map(c => <option key={c}>{c}</option>)}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <input type="number" value={f.targetAmount} onChange={e => setF(x => ({ ...x, targetAmount: e.target.value }))} placeholder="Target ₹" className="input-field text-xs" />
            <input type="date" value={f.targetDate} onChange={e => setF(x => ({ ...x, targetDate: e.target.value }))} min={today()} className="input-field text-xs" />
          </div>
          <div className="grid grid-cols-3 gap-2">
            <input type="number" value={f.currentSaved} onChange={e => setF(x => ({ ...x, currentSaved: e.target.value }))} placeholder="Saved ₹" className="input-field text-xs" />
            <input type="number" value={f.monthlyContribution} onChange={e => setF(x => ({ ...x, monthlyContribution: e.target.value }))} placeholder="Monthly ₹" className="input-field text-xs" />
            <input type="number" value={f.expectedReturn} onChange={e => setF(x => ({ ...x, expectedReturn: e.target.value }))} placeholder="Return %" className="input-field text-xs" />
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Goal'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {!goals.length
        ? <p className="text-gray-600 text-xs text-center py-4">No goals yet. Add one to see if your SIP will get you there.</p>
        : (
          <div className="space-y-3">
            {goals.map(g => {
              const statusColor = g.status === 'ACHIEVED' ? 'text-bull' : g.status === 'ON_TRACK' ? 'text-bull' : 'text-yellow-400';
              return (
                <div key={g.id} className="bg-surface-hover rounded-lg p-3">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <span className="text-white text-xs font-medium">{g.name}</span>
                      <span className="text-2xs text-gray-600">{g.category}</span>
                      <span className={`text-2xs flex items-center gap-1 ${statusColor}`}>
                        {g.status === 'SHORTFALL' ? <AlertTriangle size={10} /> : <CheckCircle size={10} />}
                        {g.status === 'ON_TRACK' ? 'On track' : g.status === 'ACHIEVED' ? 'Achieved' : 'Shortfall'}
                      </span>
                    </div>
                    <button onClick={async () => { await goalApi.delete(g.id); load(); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                  </div>
                  <div className="flex justify-between text-2xs text-gray-500 mb-1">
                    <span>{maskText(fmtINR(g.currentSaved))} of {maskText(fmtINR(g.targetAmount))}</span>
                    <span>{maskText(`${g.progressPercent.toFixed(0)}%`)}{g.targetDate ? ` · ${g.targetDate}` : ''}</span>
                  </div>
                  <div className="h-2 bg-surface rounded-full overflow-hidden mb-2">
                    <div className="h-full rounded-full bg-gradient-to-r from-brand to-bull" style={{ width: `${Math.min(100, g.progressPercent)}%` }} />
                  </div>
                  <div className="grid grid-cols-3 gap-2 text-2xs">
                    <div><span className="text-gray-600">Projected: </span><span className={`font-mono ${g.onTrack ? 'text-bull' : 'text-yellow-400'}`}>{maskText(fmtINR(g.projectedValue))}</span></div>
                    <div><span className="text-gray-600">SIP: </span><span className="font-mono text-gray-300">{maskText(fmtINR(g.monthlyContribution))}/mo</span></div>
                    {g.requiredMonthly != null && g.status === 'SHORTFALL' && (
                      <div><span className="text-gray-600">Need: </span><span className="font-mono text-yellow-400">{maskText(fmtINR(g.requiredMonthly))}/mo</span></div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
    </div>
  );
}

/* ── Tax ── */
function TaxSummary() {
  const maskText = useMaskedText();
  const [tax, setTax] = useState<TaxResponse | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => { taxApi.summary().then(r => setTax(r.data)).catch(() => {}).finally(() => setLoading(false)); }, []);

  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Receipt size={15} /></div>
        <h3 className="font-bold text-white text-sm">Tax Summary</h3>
        {tax && <span className="text-2xs text-gray-600">{tax.fyLabel}</span>}
      </div>
      {loading ? <div className="h-10 animate-pulse bg-surface-hover rounded" /> : !tax ? (
        <p className="text-gray-600 text-xs text-center py-4">No taxable investment income recorded yet.</p>
      ) : (
        <>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 mb-3">
            <StatTile label="Capital gains" value={fmtINR(tax.capitalGains)} Icon={TrendingUp} tone="brand" />
            <StatTile label="Dividends" value={fmtINR(tax.dividendIncome)} Icon={Coins} tone="gold" />
            <StatTile label="Interest" value={fmtINR(tax.interestIncome)} Icon={Landmark} tone="bull" />
            <StatTile label="Salary" value={fmtINR(tax.salaryIncome)} Icon={Wallet} tone="neutral" />
          </div>
          <div className="flex items-center justify-between bg-surface-hover rounded p-2.5 mb-3">
            <span className="text-xs text-gray-400">Estimated tax on investment income</span>
            <span className="font-mono text-white text-sm font-bold">{maskText(`${fmtINR(tax.estimatedTaxLow)} – ${fmtINR(tax.estimatedTaxHigh)}`)}</span>
          </div>
          <ul className="space-y-1">
            {tax.notes.map((n, i) => (
              <li key={i} className="text-2xs text-gray-500 flex gap-1.5"><span className="text-gray-700">•</span>{n}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
