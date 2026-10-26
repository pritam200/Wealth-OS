import { useState, useEffect, useCallback } from 'react';
import { TrendingUp, ChevronLeft, ChevronRight, Plus, Edit2, Trash2, Wallet } from 'lucide-react';
import { incomeApi, INCOME_SOURCES } from '../../api/income';
import type { IncomeResponse, IncomeSummary } from '../../api/income';
import { useMaskedText } from '../shared/Amount';
import { TransactionDetail } from '../shared/TransactionDetail';
import { AiProvenanceButton } from '../shared/AiProvenanceButton';
import { LoadFailure } from '../shared/LoadFailure';
import { categoryColor } from '../../theme/chartTheme';
import { localToday, errorText } from '../../utils/dates';
import { rentApi } from '../../api/rent';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = localToday;

/** The month's income; month and refresh are shared with the other cards on the tab (see ExpenseSection). */
export function IncomeSection({ year, month, onPrev, onNext, refreshKey, onChanged }: {
  year: number; month: number; onPrev: () => void; onNext: () => void; refreshKey: number; onChanged: () => void;
}) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<IncomeResponse[]>([]);
  const [summary, setSummary] = useState<IncomeSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [editingSource, setEditingSource] = useState<string | null>(null);

  const blank = { description: '', amount: '', source: 'Salary', incomeDate: today(), note: '' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);
  const [detail, setDetail] = useState<IncomeResponse | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [listRes, sumRes] = await Promise.all([
        incomeApi.list(year, month),
        incomeApi.summary(year, month),
      ]);
      setItems(listRes.data);
      setSummary(sumRes.data);
      setFailed(false);
    } catch { setFailed(true); }
    finally { setLoading(false); }
  }, [year, month]);

  useEffect(() => { load(); }, [load, refreshKey]);
  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  const [alreadyRecorded, setAlreadyRecorded] = useState<string | null>(null);
  // The notice is about the entry exactly as it was compared; any change, edit or close drops it,
  // so "Add anyway" can't confirm an entry the server never checked.
  useEffect(() => { setAlreadyRecorded(null); }, [f, editId, open]);

  const close = () => { setF(blank); setOpen(false); setEditId(null); setEditingSource(null); setFormError(null); };

  const submit = async (confirmSeparate = false) => {
    if (!f.description.trim()) { setFormError('Add a description.'); return; }
    if (!(Number(f.amount) > 0)) { setFormError('Enter an amount above zero.'); return; }
    setSaving(true); setFormError(null);
    try {
      const req = {
        description: f.description,
        amount: Number(f.amount),
        source: f.source,
        incomeDate: f.incomeDate || today(),
        note: f.note || undefined,
      };
      if (editId) await incomeApi.update(editId, req);
      else {
        const { data } = await incomeApi.add({ ...req, confirmSeparate });
        if (data.alreadyRecorded) {
          // Already imported from email — nothing was added. Keep the form so the user can
          // still add it if it really is a second transaction.
          setAlreadyRecorded(`Already recorded from your email: ${data.description} on ${data.incomeDate}.`);
          return;
        }
      }
      setAlreadyRecorded(null);
      close();
      onChanged();
    } catch (e) {
      setFormError(errorText(e, "Couldn't save the income. Try again."));
    } finally { setSaving(false); }
  };

  const startEdit = (e: IncomeResponse) => {
    setEditId(e.id); setOpen(true); setEditingSource(e.source); setFormError(null);
    // A capital loss is stored negative; the form edits its size and the server keeps the sign.
    setF({ description: e.description, amount: String(Math.abs(e.amount)), source: e.source,
      incomeDate: e.incomeDate, note: e.note || '' });
  };

  const del = async (e: IncomeResponse) => {
    if (!window.confirm(`Delete "${e.description}" (${fmtINR(Math.abs(e.amount))})?`)) return;
    setActionError(null);
    try { await incomeApi.delete(e.id); onChanged(); }
    catch (err) { setActionError(errorText(err, `Couldn't delete "${e.description}".`)); }
  };

  // Keep the row's own source selectable even when it isn't one a user picks (Unmatched Sale).
  const sourceOptions = editingSource && !INCOME_SOURCES.includes(editingSource)
    ? [...INCOME_SOURCES, editingSource] : INCOME_SOURCES;
  const unmatched = summary?.unmatchedSales ?? 0;

  const total = summary?.total ?? 0;
  const bySource = summary?.bySource ?? {};
  const srcEntries = Object.entries(bySource).sort((a, b) => b[1] - a[1]);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bull"><TrendingUp size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Income</h3>
          {items.length > 0 && <span className="badge-bull">{items.length}</span>}
        </div>
        <div className="flex items-center gap-2">
          <button aria-label="Previous" onClick={onPrev} className="btn-icon"><ChevronLeft size={12} /></button>
          <span className="text-xs text-gray-400 w-28 text-center">{monthLabel}</span>
          <button aria-label="Next" onClick={onNext} className="btn-icon"><ChevronRight size={12} /></button>
          <button onClick={() => (open && !editId ? close() : (close(), setOpen(true)))} className="btn-secondary text-xs flex items-center gap-1 ml-1"><Plus size={11} /> Add</button>
        </div>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Description *</label>
              <input value={f.description} onChange={e => setF(x => ({ ...x, description: e.target.value }))}
                placeholder="July salary" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Amount (₹) *</label>
              <input type="number" min="0" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
                placeholder="80000" className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Source</label>
              <select value={f.source} onChange={e => setF(x => ({ ...x, source: e.target.value }))} className="input-field text-xs">
                {sourceOptions.map(s => <option key={s}>{s}</option>)}
              </select>
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Date</label>
              <input type="date" value={f.incomeDate} onChange={e => setF(x => ({ ...x, incomeDate: e.target.value }))} className="input-field text-xs" />
            </div>
          </div>
          <input value={f.note} onChange={e => setF(x => ({ ...x, note: e.target.value }))}
            placeholder="Note (optional)" className="input-field text-xs w-full" />
          {alreadyRecorded && (
            <div className="text-2xs text-gray-400 flex items-center justify-between gap-2">
              <span>{alreadyRecorded}</span>
              <button onClick={() => submit(true)} disabled={saving} className="text-ink underline shrink-0">Add anyway</button>
            </div>
          )}
          {formError && <p className="text-2xs text-bear">{formError}</p>}
          <div className="flex gap-2">
            <button onClick={() => submit()} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : editId ? 'Update Income' : 'Save Income'}</button>
            <button onClick={close} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {actionError && <p className="text-2xs text-bear mb-2">{actionError}</p>}

      {(total !== 0 || unmatched > 0) && !failed && (
        <div className="mb-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-2xs text-gray-500">Total earned</span>
            <span className="font-bold text-bull font-mono text-sm">{maskText(fmtINR(total))}</span>
          </div>
          {srcEntries.length > 0 && (
            <div className="space-y-1">
              {srcEntries.map(([src, amt]) => {
                const pct = total > 0 ? (amt / total * 100) : 0;
                const color = categoryColor(src);
                return (
                  <div key={src}>
                    <div className="flex justify-between text-2xs mb-0.5">
                      <span className="text-gray-400">{src}</span>
                      <span className="text-ink font-mono">{maskText(fmtINR(amt))} <span className="text-gray-600">({maskText(`${pct.toFixed(0)}%`)})</span></span>
                    </div>
                    <div className="h-1 bg-surface-hover rounded-full overflow-hidden">
                      <div className="h-full rounded-full" style={{ width: `${pct}%`, backgroundColor: color }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          {unmatched > 0 && (
            <div className="flex items-center justify-between text-2xs text-gray-500 pt-1 border-t border-surface-border/40">
              <span title="Money from selling shares or funds whose purchase isn't recorded yet. It's the sale value, not earnings, so it isn't counted. It's matched once the purchase is imported.">
                Sale proceeds not yet matched (not counted)
              </span>
              <span className="font-mono">{maskText(fmtINR(unmatched))}</span>
            </div>
          )}
        </div>
      )}

      {loading && !items.length
        ? <div className="h-10 animate-pulse bg-surface-hover rounded" />
        : failed
        ? <LoadFailure what="your income" onRetry={load} />
        : !items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No income recorded for {monthLabel}.</p>
        : (
          <div className="max-h-64 overflow-y-auto divide-y divide-surface-border/30">
            {items.map(e => {
              const displayName = e.payer ? `${e.source} from ${e.payer}` : e.description;
              const subtitle = [e.source, e.paymentMethod, e.incomeDate].filter(Boolean).join(' · ');
              return (
                <div key={e.id} className="flex items-center justify-between py-1.5 cursor-pointer hover:bg-surface-hover/50 rounded -mx-1 px-1 transition-colors"
                     onClick={() => setDetail(e)}>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: categoryColor(e.source) }} />
                      <span className="text-ink text-xs font-medium truncate">{displayName}</span>
                    </div>
                    <div className="text-2xs text-gray-600 ml-3 truncate">{subtitle}</div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-2">
                    <span className="text-bull text-xs font-mono font-semibold">{maskText(fmtINR(e.amount))}</span>
                    <AiProvenanceButton referenceId={e.sourceEmailId} record={{ kind: 'income', id: e.id }} />
                    <button onClick={(ev) => { ev.stopPropagation(); startEdit(e); }} className="btn-icon text-gray-500 hover:text-ink p-0.5" title="Edit income"><Edit2 size={11} /></button>
                    <button aria-label="Delete" onClick={(ev) => { ev.stopPropagation(); del(e); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                  </div>
                </div>
              );
            })}
          </div>
        )
      }

      {detail && (
        <TransactionDetail
          open={!!detail}
          onClose={() => setDetail(null)}
          title={detail.payer || detail.description}
          badgeColor={categoryColor(detail.source)}
          badgeLabel={detail.source}
          amount={detail.amount}
          fields={[
            { label: 'Payer', value: detail.payer },
            { label: 'Description', value: detail.description !== detail.payer ? detail.description : undefined },
            { label: 'Source', value: detail.source },
            { label: 'Date', value: detail.incomeDate },
            { label: 'Payment Method', value: detail.paymentMethod },
            { label: 'Origin', value: detail.sourceEmailId ? 'Auto-imported from email' : 'Manual entry' },
            { label: 'Note', value: detail.note },
          ]}
        />
      )}
    </div>
  );
}

export function SavingsRatioCard({ year, month, refreshKey }: { year: number; month: number; refreshKey: number }) {
  const maskText = useMaskedText();
  const [income, setIncome] = useState(0);
  const [spent, setSpent] = useState(0);
  const [rent, setRent] = useState(0);

  useEffect(() => {
    const ym = `${year}-${String(month).padStart(2, '0')}`;
    Promise.all([
      incomeApi.summary(year, month),
      import('../../api/expense').then(m => m.expenseApi.summary(year, month)),
      // Rent has its own ledger, so it isn't in the expense total; it is still money out.
      rentApi.list().catch(() => ({ data: [] as Awaited<ReturnType<typeof rentApi.list>>['data'] })),
    ]).then(([inc, exp, rents]) => {
      setIncome(inc.data?.total ?? 0);
      setSpent(exp.data?.total ?? 0);
      setRent(rents.data.filter(r => r.status === 'PAID' && r.month?.startsWith(ym))
        .reduce((sum, r) => sum + (r.amount || 0), 0));
    }).catch(() => {});
  }, [year, month, refreshKey]);

  const expense = spent + rent;
  const saved = income - expense;
  const ratio = income > 0 ? (saved / income * 100) : 0;
  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  if (income === 0 && expense === 0) return null;

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Wallet size={15} /></div>
        <h3 className="font-bold text-ink text-sm">Savings — {monthLabel}</h3>
      </div>
      {rent > 0 && <p className="text-2xs text-gray-600 -mt-2 mb-2">Expenses include {maskText(fmtINR(rent))} rent paid.</p>}
      <div className="grid grid-cols-3 gap-3 mb-3">
        <div className="bg-surface-hover rounded p-2">
          <div className="stat-label text-2xs">Income</div>
          <div className="font-bold font-mono text-bull text-sm">{maskText(fmtINR(income))}</div>
        </div>
        <div className="bg-surface-hover rounded p-2">
          <div className="stat-label text-2xs">Expenses</div>
          <div className="font-bold font-mono text-bear text-sm">{maskText(fmtINR(expense))}</div>
        </div>
        <div className="bg-surface-hover rounded p-2">
          <div className="stat-label text-2xs">Saved</div>
          <div className={`font-bold font-mono text-sm ${saved >= 0 ? 'text-bull' : 'text-bear'}`}>{maskText(fmtINR(saved))}</div>
        </div>
      </div>
      <div>
        <div className="flex justify-between text-2xs text-gray-500 mb-1">
          <span>Savings rate</span>
          <span className={ratio >= 20 ? 'text-bull' : ratio >= 10 ? 'text-neutral' : 'text-bear'}>{maskText(`${ratio.toFixed(1)}%`)}</span>
        </div>
        <div className="h-2 bg-surface-hover rounded-full overflow-hidden">
          <div className="h-full rounded-full bg-brand-gradient transition-all"
            style={{ width: `${Math.min(100, Math.max(0, ratio))}%` }} />
        </div>
        <div className="text-2xs text-gray-600 mt-1">{ratio >= 30 ? 'Excellent savings!' : ratio >= 20 ? 'Good savings rate' : ratio >= 10 ? 'Try to save more' : 'High expenses this month'}</div>
      </div>
    </div>
  );
}
