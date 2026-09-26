import { useState, useEffect, useCallback } from 'react';
import {
  Bell, Target, Receipt, Plus, Trash2, CheckCircle, AlertTriangle, CalendarClock,
  TrendingUp, Coins, Landmark, Wallet, HelpCircle,
} from 'lucide-react';
import { reminderApi, goalApi, taxApi, GOAL_CATEGORIES } from '../../api/planning';
import type { Reminder, GoalResponse, TaxResponse, GoalWhatIfResponse } from '../../api/planning';
import { scheduledInvestmentApi } from '../../api/scheduledInvestment';
import type { RecurringInvestment } from '../../api/scheduledInvestment';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';
import { LoadFailure } from '../../components/shared/LoadFailure';
import { formatINR } from '../../utils/currency';

const fmtINR = formatINR;

export function Tab12Planning() {
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-neutral/10 border border-neutral/25 flex items-center justify-center text-neutral shrink-0">
          <Target size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Financial Planning</h2>
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
          <h3 className="font-bold text-ink text-sm">Scheduled Investments</h3>
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
                  <span className="text-ink text-xs font-medium truncate max-w-[160px]">{i.label}</span>
                  <span className="text-2xs text-gray-500 font-mono">{maskText(fmtINR(i.amount))}/mo</span>
                </div>
                <button aria-label="Delete" onClick={async () => { await scheduledInvestmentApi.delete(i.id); load(); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
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
  OVERDUE: 'border-bear/40 bg-bear/5', DUE_SOON: 'border-neutral/40 bg-neutral/5', UPCOMING: 'border-surface-border bg-surface-hover',
};
const SEV_DOT: Record<string, string> = { OVERDUE: 'bg-bear', DUE_SOON: 'bg-neutral', UPCOMING: 'bg-gray-500' };

export function RemindersPanel() {
  const maskText = useMaskedText();
  const [items, setItems] = useState<Reminder[]>([]);
  const [loading, setLoading] = useState(true);
  // An error here must not render as "Nothing due soon" — that tells someone with an overdue
  // card bill the opposite of the truth.
  const [failed, setFailed] = useState(false);
  const load = useCallback(() => {
    setLoading(true); setFailed(false);
    reminderApi.list()
      .then(r => setItems(r.data))
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);
  useEffect(() => { load(); }, [load]);

  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Bell size={15} /></div>
        <h3 className="font-bold text-ink text-sm">Reminders</h3>
        {items.length > 0 && <span className="badge-neutral">{items.length}</span>}
      </div>
      {loading
        ? <div className="h-10 animate-pulse bg-surface-hover rounded" />
        : failed
        ? <LoadFailure what="your reminders" onRetry={load} />
        : !items.length
          ? <p className="text-gray-600 text-xs text-center py-4">Nothing due soon. FD maturities, RD installments and card bills will appear here automatically.</p>
          : (
            <div className="space-y-2">
              {items.map((r, i) => (
                <div key={i} className={`flex items-center justify-between rounded-lg border p-2.5 ${SEV_STYLE[r.severity]}`}>
                  <div className="flex items-center gap-2 min-w-0">
                    <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${SEV_DOT[r.severity]}`} />
                    <div className="min-w-0">
                      <div className="text-ink text-xs font-medium truncate">{r.title}</div>
                      <div className="text-2xs text-gray-500">{r.subtitle}</div>
                    </div>
                  </div>
                  <div className="text-right shrink-0 ml-2">
                    {r.amount > 0 && <div className="text-xs font-mono text-ink">{maskText(fmtINR(r.amount))}</div>}
                    <div className={`text-2xs ${r.daysUntil < 0 ? 'text-bear' : r.daysUntil <= 7 ? 'text-neutral' : 'text-gray-500'}`}>
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

  const [failed, setFailed] = useState(false);
  const load = useCallback(async () => {
    try { setGoals((await goalApi.list()).data); setFailed(false); }
    catch { setFailed(true); }
  }, []);
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
          <h3 className="font-bold text-ink text-sm">Financial Goals</h3>
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

      {failed
        ? <LoadFailure what="your goals" onRetry={load} />
        : !goals.length
        ? <p className="text-gray-600 text-xs text-center py-4">No goals yet. Add one to see if your SIP will get you there.</p>
        : (
          <div className="space-y-3">
            {goals.map(g => {
              const statusColor = g.status === 'ACHIEVED' ? 'text-bull' : g.status === 'ON_TRACK' ? 'text-bull' : 'text-neutral';
              return (
                <div key={g.id} className="bg-surface-hover rounded-lg p-3">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <span className="text-ink text-xs font-medium">{g.name}</span>
                      <span className="text-2xs text-gray-600">{g.category}</span>
                      <span className={`text-2xs flex items-center gap-1 ${statusColor}`}>
                        {g.status === 'SHORTFALL' ? <AlertTriangle size={10} /> : <CheckCircle size={10} />}
                        {g.status === 'ON_TRACK' ? 'On track' : g.status === 'ACHIEVED' ? 'Achieved' : 'Shortfall'}
                      </span>
                    </div>
                    <button aria-label="Delete" onClick={async () => { await goalApi.delete(g.id); load(); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                  </div>
                  <div className="flex justify-between text-2xs text-gray-500 mb-1">
                    <span>{maskText(fmtINR(g.currentSaved))} of {maskText(fmtINR(g.targetAmount))}</span>
                    <span>{maskText(`${g.progressPercent.toFixed(0)}%`)}{g.targetDate ? ` · ${g.targetDate}` : ''}</span>
                  </div>
                  <div className="h-2 bg-surface rounded-full overflow-hidden mb-2">
                    <div className="h-full rounded-full bg-gradient-to-r from-brand to-bull" style={{ width: `${Math.min(100, g.progressPercent)}%` }} />
                  </div>
                  <div className="grid grid-cols-3 gap-2 text-2xs mb-2">
                    <div><span className="text-gray-600">Projected: </span><span className={`font-mono ${g.onTrack ? 'text-bull' : 'text-neutral'}`}>{maskText(fmtINR(g.projectedValue))}</span></div>
                    <div><span className="text-gray-600">SIP: </span><span className="font-mono text-gray-300">{maskText(fmtINR(g.monthlyContribution))}/mo</span></div>
                    {g.requiredMonthly != null && g.status === 'SHORTFALL' && (
                      <div><span className="text-gray-600">Need: </span><span className="font-mono text-neutral">{maskText(fmtINR(g.requiredMonthly))}/mo</span></div>
                    )}
                  </div>
                  <GoalWhatIfPanel goal={g} />
                </div>
              );
            })}
          </div>
        )}
    </div>
  );
}

/* ── Goal what-if ── pure simulation, never touches the real goal/SIP */
function GoalWhatIfPanel({ goal }: { goal: GoalResponse }) {
  const maskText = useMaskedText();
  const [open, setOpen] = useState(false);
  const [type, setType] = useState<'PAUSE_SIP' | 'LUMP_SUM'>('PAUSE_SIP');
  const [startMonth, setStartMonth] = useState('1');
  const [pauseMonths, setPauseMonths] = useState('3');
  const [lumpSumAmount, setLumpSumAmount] = useState('');
  const [result, setResult] = useState<GoalWhatIfResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [failed, setFailed] = useState(false);

  const run = async () => {
    setRunning(true); setFailed(false); setResult(null);
    try {
      const { data } = await goalApi.whatIf(goal.id, {
        adjustmentType: type,
        startMonth: Number(startMonth) || 1,
        pauseMonths: type === 'PAUSE_SIP' ? Number(pauseMonths) || 0 : undefined,
        lumpSumAmount: type === 'LUMP_SUM' ? Number(lumpSumAmount) || 0 : undefined,
      });
      setResult(data);
    } catch { setFailed(true); } finally { setRunning(false); }
  };

  if (!open) {
    return (
      <button onClick={() => setOpen(true)} className="text-2xs text-brand flex items-center gap-1 hover:underline">
        <HelpCircle size={11} /> What if…
      </button>
    );
  }

  return (
    <div className="bg-surface rounded-lg p-2.5 border border-surface-border space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-2xs font-medium text-ink">What if this SIP changes?</span>
        <button onClick={() => { setOpen(false); setResult(null); }} className="text-2xs text-gray-600 hover:text-ink">Close</button>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <select value={type} onChange={e => { setType(e.target.value as any); setResult(null); }} className="input-field text-2xs">
          <option value="PAUSE_SIP">Pause SIP</option>
          <option value="LUMP_SUM">Add lump sum</option>
        </select>
        <input type="number" min="1" value={startMonth} onChange={e => setStartMonth(e.target.value)} placeholder="Starting in (months)" className="input-field text-2xs" />
      </div>
      {type === 'PAUSE_SIP' ? (
        <input type="number" min="1" value={pauseMonths} onChange={e => setPauseMonths(e.target.value)} placeholder="Pause for how many months" className="input-field text-2xs w-full" />
      ) : (
        <input type="number" min="0" value={lumpSumAmount} onChange={e => setLumpSumAmount(e.target.value)} placeholder="Lump sum amount ₹" className="input-field text-2xs w-full" />
      )}
      <button onClick={run} disabled={running} className="btn-secondary text-2xs py-1 w-full">{running ? 'Simulating…' : 'See the effect'}</button>

      {failed && <p className="text-2xs text-bear">Couldn't run that simulation. Try again.</p>}

      {result && (
        <div className="grid grid-cols-2 gap-2 pt-1 border-t border-surface-border">
          <div>
            <div className="text-2xs text-gray-600 mb-0.5">Baseline</div>
            <div className="font-mono text-2xs text-gray-300">{maskText(fmtINR(result.baseline.projectedValue))}</div>
            <div className="text-2xs text-gray-600">{result.baseline.completionDate ? `done ${result.baseline.completionDate}` : 'not reached'}</div>
          </div>
          <div>
            <div className="text-2xs text-gray-600 mb-0.5">With this change</div>
            <div className={`font-mono text-2xs ${result.projectedValueDelta >= 0 ? 'text-bull' : 'text-neutral'}`}>{maskText(fmtINR(result.scenario.projectedValue))}</div>
            <div className="text-2xs text-gray-600">{result.scenario.completionDate ? `done ${result.scenario.completionDate}` : 'not reached'}</div>
          </div>
          {result.completionDelayMonths != null && (
            <div className="col-span-2 text-2xs text-gray-500">
              {result.completionDelayMonths === 0
                ? 'No change to completion timing.'
                : result.completionDelayMonths > 0
                  ? `Delays your goal by ${result.completionDelayMonths} month${result.completionDelayMonths === 1 ? '' : 's'}.`
                  : `Pulls your goal forward by ${Math.abs(result.completionDelayMonths)} month${Math.abs(result.completionDelayMonths) === 1 ? '' : 's'}.`}
            </div>
          )}
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
  const [failed, setFailed] = useState(false);
  const [exporting, setExporting] = useState(false);
  const loadTax = useCallback(() => {
    setLoading(true); setFailed(false);
    taxApi.summary()
      .then(r => setTax(r.data))
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);
  useEffect(() => { loadTax(); }, [loadTax]);

  const handleExport = useCallback(() => {
    if (!tax) return;
    setExporting(true);
    // fyLabel is "FY 2026-27" — the export endpoint takes the "2026-27" part.
    const financialYear = tax.fyLabel.replace(/^FY\s*/, '');
    taxApi.exportCsv(financialYear)
      .then(r => {
        const url = URL.createObjectURL(r.data);
        const a = document.createElement('a');
        a.href = url;
        a.download = `capital-gains-${financialYear}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch(() => alert('Could not generate the export. Please try again.'))
      .finally(() => setExporting(false));
  }, [tax]);

  return (
    <div className="card">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Receipt size={15} /></div>
        <h3 className="font-bold text-ink text-sm">Tax Summary</h3>
        {tax && <span className="text-2xs text-gray-600">{tax.fyLabel}</span>}
        {tax && (
          <button onClick={handleExport} disabled={exporting}
                  className="btn-ghost text-2xs ml-auto disabled:opacity-50">
            {exporting ? 'Exporting…' : 'Export for ITR filing'}
          </button>
        )}
      </div>
      {loading ? <div className="h-10 animate-pulse bg-surface-hover rounded" /> : failed ? (
        <LoadFailure what="your tax summary" onRetry={loadTax} />
      ) : !tax ? (
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
            <span className="font-mono text-ink text-sm font-bold">{maskText(`${fmtINR(tax.estimatedTaxLow)} – ${fmtINR(tax.estimatedTaxHigh)}`)}</span>
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
