import { useState, useEffect, useCallback } from 'react';
import {
  Bell, Target, Receipt, Plus, Trash2, CheckCircle, AlertTriangle, CalendarClock,
  TrendingUp, Coins, Landmark, Wallet, HelpCircle, Pencil,
} from 'lucide-react';
import { reminderApi, goalApi, taxApi, GOAL_CATEGORIES } from '../../api/planning';
import type { Reminder, GoalResponse, TaxResponse, GoalWhatIfResponse, GoalLinkOption, GoalLinkOptions } from '../../api/planning';
import { scheduledInvestmentApi } from '../../api/scheduledInvestment';
import type { RecurringInvestment } from '../../api/scheduledInvestment';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';
import { LoadFailure } from '../../components/shared/LoadFailure';
import { formatINR } from '../../utils/currency';
import { localToday, errorText } from '../../utils/dates';

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
const INSTALLMENT_DOT: Record<string, string> = {
  COMPLETED: 'bg-bull', PARTIAL: 'bg-neutral', FAILED: 'bg-bear', MISSED: 'bg-bear',
  PAUSED: 'bg-gray-700', UPCOMING: 'bg-gray-500',
};

/** "3 completed, 1 partial, 1 failed" — only the states that occurred. */
function installmentCounts(i: RecurringInvestment): string {
  const parts: string[] = [`${i.completedCount} completed`];
  if (i.partialCount) parts.push(`${i.partialCount} partial`);
  if (i.failedCount) parts.push(`${i.failedCount} failed`);
  if (i.missedCount) parts.push(`${i.missedCount} missed`);
  if (i.pausedCount) parts.push(`${i.pausedCount} paused`);
  return parts.join(', ');
}

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
                  <span key={idx} title={`${inst.dueDate} — ${inst.status}${inst.expectedAmount != null ? ` · due ${maskText(fmtINR(inst.expectedAmount))}` : ''}${inst.actualAmount != null ? ` · bought ${maskText(fmtINR(inst.actualAmount))}` : ''}${inst.note ? ` · ${inst.note}` : ''}`} className={`w-2 h-2 rounded-full ${INSTALLMENT_DOT[inst.status]}`} />
                ))}
                {i.type === 'SIP' && i.linkedSymbol && (
                  <span className="text-2xs text-gray-600 ml-1">{installmentCounts(i)}</span>
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
const today = localToday;
type GoalForm = {
  name: string; category: string; targetAmount: string; currentSaved: string; monthlyContribution: string;
  expectedReturn: string; inflationRate: string; targetDate: string; linkedHoldingIds: number[]; linkedSipIds: number[];
};
const blankGoal: GoalForm = {
  name: '', category: 'Retirement', targetAmount: '', currentSaved: '', monthlyContribution: '',
  expectedReturn: '10', inflationRate: '6', targetDate: '', linkedHoldingIds: [], linkedSipIds: [],
};
const goalToForm = (g: GoalResponse): GoalForm => ({
  name: g.name, category: g.category || 'Other', targetAmount: String(g.targetAmount),
  currentSaved: g.currentSaved ? String(g.currentSaved) : '', monthlyContribution: g.monthlyContribution ? String(g.monthlyContribution) : '',
  expectedReturn: String(g.expectedReturn ?? 10), inflationRate: String(g.inflationRate ?? 0), targetDate: g.targetDate ?? '',
  linkedHoldingIds: g.linkedHoldingIds ?? [], linkedSipIds: g.linkedSipIds ?? [],
});
/** What's wrong with the form, or null when it can be saved. */
function goalFormError(f: GoalForm): string | null {
  const n = (s: string) => (s === '' ? 0 : Number(s));
  if (!f.name.trim()) return 'Give the goal a name.';
  if (!(Number(f.targetAmount) > 0)) return 'Set a target amount above zero.';
  if (n(f.currentSaved) < 0 || n(f.monthlyContribution) < 0) return "Amounts can't be negative.";
  if (Number.isNaN(n(f.expectedReturn)) || n(f.expectedReturn) < 0 || n(f.expectedReturn) > 50) return 'Expected return must be between 0 and 50%.';
  if (Number.isNaN(n(f.inflationRate)) || n(f.inflationRate) < 0 || n(f.inflationRate) > 30) return 'Inflation must be between 0 and 30%.';
  return null;
}
const STATUS_LABEL: Record<GoalResponse['status'], string> = {
  ON_TRACK: 'On track', ACHIEVED: 'Achieved', SHORTFALL: 'Shortfall', OVERDUE: 'Overdue',
};

function GoalsSection() {
  const maskText = useMaskedText();
  const [goals, setGoals] = useState<GoalResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [f, setF] = useState<GoalForm>(blankGoal);
  const [options, setOptions] = useState<GoalLinkOptions | null>(null);

  const [failed, setFailed] = useState(false);
  const load = useCallback(async () => {
    try { setGoals((await goalApi.list()).data); setFailed(false); }
    catch { setFailed(true); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const openForm = (g?: GoalResponse) => {
    setF(g ? goalToForm(g) : blankGoal);
    setEditingId(g ? g.id : null);
    setSaveError(null);
    setOpen(true);
    if (!options) goalApi.linkOptions().then(r => setOptions(r.data)).catch(() => setOptions({ holdings: [], sips: [] }));
  };
  const close = () => { setF(blankGoal); setEditingId(null); setSaveError(null); setOpen(false); };

  const formError = goalFormError(f);
  const submit = async () => {
    if (formError) { setSaveError(formError); return; }
    setSaving(true); setSaveError(null);
    const payload = {
      name: f.name.trim(), category: f.category, targetAmount: Number(f.targetAmount),
      currentSaved: f.currentSaved ? Number(f.currentSaved) : 0,
      monthlyContribution: f.monthlyContribution ? Number(f.monthlyContribution) : 0,
      expectedReturn: f.expectedReturn ? Number(f.expectedReturn) : 10,
      inflationRate: f.inflationRate ? Number(f.inflationRate) : 0,
      targetDate: f.targetDate || undefined,
      linkedHoldingIds: f.linkedHoldingIds, linkedSipIds: f.linkedSipIds,
    };
    try {
      if (editingId != null) await goalApi.update(editingId, payload);
      else await goalApi.add(payload);
      close(); await load();
    } catch (e) {
      setSaveError(errorText(e, "Couldn't save the goal. Try again."));
    } finally { setSaving(false); }
  };

  const remove = async (g: GoalResponse) => {
    if (!window.confirm(`Delete the goal "${g.name}"? This can't be undone.`)) return;
    setActionError(null);
    try { await goalApi.delete(g.id); }
    catch (e) { setActionError(errorText(e, `Couldn't delete "${g.name}".`)); }
    finally { await load(); }
  };

  const toggle = (key: 'linkedHoldingIds' | 'linkedSipIds', id: number) =>
    setF(x => ({ ...x, [key]: x[key].includes(id) ? x[key].filter(i => i !== id) : [...x[key], id] }));

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-gold"><Target size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Financial Goals</h3>
        </div>
        <button onClick={() => (open ? close() : openForm())} className="btn-secondary text-xs flex items-center gap-1"><Plus size={11} /> Add Goal</button>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="text-2xs font-medium text-ink">{editingId != null ? 'Edit goal' : 'New goal'}</div>
          <div className="grid grid-cols-2 gap-2">
            <input value={f.name} onChange={e => setF(x => ({ ...x, name: e.target.value }))} placeholder="Goal name (e.g. Retirement)" className="input-field text-xs" />
            <select value={f.category} onChange={e => setF(x => ({ ...x, category: e.target.value }))} className="input-field text-xs">
              {GOAL_CATEGORIES.map(c => <option key={c}>{c}</option>)}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <label className="text-2xs text-gray-600">Target ₹ (today's rupees)
              <input type="number" min="0" value={f.targetAmount} onChange={e => setF(x => ({ ...x, targetAmount: e.target.value }))} className="input-field text-xs w-full" />
            </label>
            <label className="text-2xs text-gray-600">Target date (blank for none)
              <input type="date" value={f.targetDate} onChange={e => setF(x => ({ ...x, targetDate: e.target.value }))} min={editingId == null ? today() : undefined} className="input-field text-xs w-full" />
            </label>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <label className="text-2xs text-gray-600">Other savings ₹ (not linked below)
              <input type="number" min="0" value={f.currentSaved} onChange={e => setF(x => ({ ...x, currentSaved: e.target.value }))} className="input-field text-xs w-full" />
            </label>
            <label className="text-2xs text-gray-600">Other monthly ₹ (not linked below)
              <input type="number" min="0" value={f.monthlyContribution} onChange={e => setF(x => ({ ...x, monthlyContribution: e.target.value }))} className="input-field text-xs w-full" />
            </label>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <label className="text-2xs text-gray-600">Expected return % a year
              <input type="number" min="0" max="50" value={f.expectedReturn} onChange={e => setF(x => ({ ...x, expectedReturn: e.target.value }))} className="input-field text-xs w-full" />
            </label>
            <label className="text-2xs text-gray-600">Inflation % a year
              <input type="number" min="0" max="30" value={f.inflationRate} onChange={e => setF(x => ({ ...x, inflationRate: e.target.value }))} className="input-field text-xs w-full" />
            </label>
          </div>

          <GoalLinkPicker title="Holdings counted toward this goal (current value)" options={options?.holdings} selected={f.linkedHoldingIds}
            onToggle={id => toggle('linkedHoldingIds', id)} empty="No open holdings yet." />
          <GoalLinkPicker title="SIPs counted toward this goal (monthly amount, while active)" options={options?.sips} selected={f.linkedSipIds}
            onToggle={id => toggle('linkedSipIds', id)} empty="No SIPs tracked yet — add one under Recurring Investments." perMonth />

          {saveError && <p className="text-2xs text-bear">{saveError}</p>}
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving || !!formError} title={formError ?? undefined} className="btn-primary text-xs py-1.5 flex-1">
              {saving ? 'Saving…' : editingId != null ? 'Update Goal' : 'Save Goal'}
            </button>
            <button onClick={close} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {actionError && <p className="text-2xs text-bear mb-2">{actionError}</p>}
      {loading
        ? <div className="h-10 animate-pulse bg-surface-hover rounded" />
        : failed
        ? <LoadFailure what="your goals" onRetry={load} />
        : !goals.length
        ? <p className="text-gray-600 text-xs text-center py-4">No goals yet. Add one to see if your SIP will get you there.</p>
        : (
          <div className="space-y-3">
            {goals.map(g => {
              const statusColor = g.status === 'ACHIEVED' || g.status === 'ON_TRACK' ? 'text-bull' : g.status === 'OVERDUE' ? 'text-bear' : 'text-neutral';
              const inflated = g.inflatedTarget != null && Math.round(g.inflatedTarget) > Math.round(g.targetAmount);
              const linked = (g.linkedHoldingIds?.length ?? 0) + (g.linkedSipIds?.length ?? 0);
              return (
                <div key={g.id} className="bg-surface-hover rounded-lg p-3">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <span className="text-ink text-xs font-medium">{g.name}</span>
                      <span className="text-2xs text-gray-600">{g.category}</span>
                      <span className={`text-2xs flex items-center gap-1 ${statusColor}`}>
                        {g.status === 'SHORTFALL' || g.status === 'OVERDUE' ? <AlertTriangle size={10} /> : <CheckCircle size={10} />}
                        {STATUS_LABEL[g.status] ?? g.status}
                      </span>
                    </div>
                    <div className="flex items-center gap-1">
                      <button aria-label="Edit" onClick={() => openForm(g)} className="btn-icon text-gray-700 hover:text-ink p-0.5"><Pencil size={11} /></button>
                      <button aria-label="Delete" onClick={() => remove(g)} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                    </div>
                  </div>
                  <div className="flex justify-between text-2xs text-gray-500 mb-1">
                    <span>{maskText(fmtINR(g.totalSaved ?? g.currentSaved))} of {maskText(fmtINR(inflated ? g.inflatedTarget : g.targetAmount))}</span>
                    <span>{maskText(`${g.progressPercent.toFixed(0)}%`)}{g.targetDate ? ` · ${g.targetDate}` : ''}</span>
                  </div>
                  {inflated && (
                    <div className="text-2xs text-gray-600 mb-1">{maskText(fmtINR(g.targetAmount))} today is {maskText(fmtINR(g.inflatedTarget))} by then at {g.inflationRate}% inflation</div>
                  )}
                  <div className="h-2 bg-surface rounded-full overflow-hidden mb-2">
                    <div className="h-full rounded-full bg-gradient-to-r from-brand to-bull" style={{ width: `${Math.min(100, g.progressPercent)}%` }} />
                  </div>
                  {g.status === 'OVERDUE' ? (
                    <p className="text-2xs text-bear mb-2">The target date has passed — {maskText(fmtINR(g.shortfallAmount))} short. Edit the goal to set a new date.</p>
                  ) : (
                    <div className="grid grid-cols-3 gap-2 text-2xs mb-2">
                      <div><span className="text-gray-600">Projected: </span><span className={`font-mono ${g.onTrack ? 'text-bull' : 'text-neutral'}`}>{maskText(fmtINR(g.projectedValue))}</span></div>
                      <div><span className="text-gray-600">Monthly: </span><span className="font-mono text-gray-300">{maskText(fmtINR(g.totalMonthly ?? g.monthlyContribution))}/mo</span></div>
                      {g.requiredMonthly != null && g.status === 'SHORTFALL' && (
                        <div><span className="text-gray-600">Need: </span><span className="font-mono text-neutral">{maskText(fmtINR(g.requiredMonthly))}/mo</span></div>
                      )}
                    </div>
                  )}
                  {linked > 0 && (
                    <div className="text-2xs text-gray-600 mb-2">
                      Includes {g.linkedHoldingIds.length} linked holding{g.linkedHoldingIds.length === 1 ? '' : 's'} ({maskText(fmtINR(g.linkedValue))})
                      {g.linkedSipIds.length > 0 && <> and {g.linkedSipIds.length} SIP{g.linkedSipIds.length === 1 ? '' : 's'} ({maskText(fmtINR(g.linkedMonthly))}/mo active)</>}
                    </div>
                  )}
                  <GoalWhatIfPanel goal={g} />
                </div>
              );
            })}
          </div>
        )}
    </div>
  );
}

function GoalLinkPicker({ title, options, selected, onToggle, empty, perMonth }: {
  title: string; options: GoalLinkOption[] | undefined; selected: number[];
  onToggle: (id: number) => void; empty: string; perMonth?: boolean;
}) {
  const maskText = useMaskedText();
  // A linked holding that has since been sold no longer appears among the options; it counts nothing.
  const missing = options ? selected.filter(id => !options.some(o => o.id === id)).length : 0;
  return (
    <div>
      <div className="text-2xs text-gray-600 mb-1">{title}</div>
      {!options ? <div className="h-6 animate-pulse bg-surface rounded" />
        : !options.length ? <p className="text-2xs text-gray-600">{empty}</p>
        : (
          <div className="max-h-32 overflow-y-auto bg-surface rounded border border-surface-border divide-y divide-surface-border">
            {options.map(o => (
              <label key={o.id} className="flex items-center gap-2 px-2 py-1 text-2xs cursor-pointer">
                <input type="checkbox" checked={selected.includes(o.id)} onChange={() => onToggle(o.id)} />
                <span className="text-ink truncate flex-1">{o.label}</span>
                {o.detail && <span className="text-gray-600 shrink-0">{o.detail}</span>}
                <span className="font-mono text-gray-400 shrink-0">{maskText(fmtINR(o.amount))}{perMonth ? '/mo' : ''}</span>
              </label>
            ))}
          </div>
        )}
      {missing > 0 && <p className="text-2xs text-gray-600 mt-1">{missing} linked item{missing === 1 ? ' is' : 's are'} no longer held and count nothing; saving removes {missing === 1 ? 'it' : 'them'}.</p>}
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
          {result.scenarioNeverCompletes && (
            <div className="col-span-2 text-2xs text-bear">With this change the goal is no longer reached within 50 years.</div>
          )}
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
          {(tax.tdsDeducted ?? 0) > 0 && (
            <div className="flex items-center justify-between rounded px-2.5 -mt-2 mb-3">
              <span className="text-2xs text-gray-500">TDS already deducted (recorded)</span>
              <span className="font-mono text-2xs text-gray-400">{maskText(fmtINR(tax.tdsDeducted ?? 0))}</span>
            </div>
          )}
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
