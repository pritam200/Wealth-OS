import { useState, useEffect, useCallback } from 'react';
import { ShoppingCart, Plus, Edit2, Trash2, ChevronLeft, ChevronRight, Search, X } from 'lucide-react';
import { expenseApi, EXPENSE_CATEGORIES, NOT_SPENDING, categoryLabel } from '../../api/expense';
import type { ExpenseResponse, ExpenseSummary } from '../../api/expense';
import { ledgerApi } from '../../api/ledger';
import type { CashAccount } from '../../api/ledger';
import { useMaskedText } from '../shared/Amount';
import { TransactionDetail } from '../shared/TransactionDetail';
import { AiProvenanceButton } from '../shared/AiProvenanceButton';
import { LoadFailure } from '../shared/LoadFailure';
import { categoryColor, categoryIcon } from '../../theme/chartTheme';
import { localToday, errorText } from '../../utils/dates';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = localToday;

const isRefund = (e: ExpenseResponse) => e.refundOfExpenseId != null || e.amount < 0;

/**
 * The month's expenses. {@code year}/{@code month} come from the tab so every card on it shows
 * the same month; {@code onChanged} tells the tab to refresh the others after an add, edit or delete.
 */
export function ExpenseSection({ year, month, onPrev, onNext, refreshKey, onChanged }: {
  year: number; month: number; onPrev: () => void; onNext: () => void; refreshKey: number; onChanged: () => void;
}) {
  const maskText = useMaskedText();
  const [items, setItems] = useState<ExpenseResponse[]>([]);
  const [summary, setSummary] = useState<ExpenseSummary | null>(null);
  const [accounts, setAccounts] = useState<CashAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const blank = { description: '', amount: '', category: 'Food', expenseDate: today(),
    merchant: '', paymentMethod: '', cashAccountId: '', note: '', rememberForMerchant: true };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);
  const [editing, setEditing] = useState<ExpenseResponse | null>(null);
  const [detail, setDetail] = useState<ExpenseResponse | null>(null);

  // Filters: a category and/or text narrow the month's list on the server.
  const [filterCategory, setFilterCategory] = useState('');
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const filtering = !!filterCategory || !!appliedQuery;

  const monthStart = `${year}-${String(month).padStart(2, '0')}-01`;
  const monthEnd = `${year}-${String(month).padStart(2, '0')}-${String(new Date(year, month, 0).getDate()).padStart(2, '0')}`;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [listRes, sumRes] = await Promise.all([
        filtering
          ? expenseApi.search({ from: monthStart, to: monthEnd, category: filterCategory || undefined, q: appliedQuery || undefined })
          : expenseApi.list(year, month),
        expenseApi.summary(year, month),
      ]);
      setItems(listRes.data);
      setSummary(sumRes.data);
      setFailed(false);
    } catch { setFailed(true); }
    finally { setLoading(false); }
  }, [year, month, filtering, filterCategory, appliedQuery, monthStart, monthEnd]);

  useEffect(() => { load(); }, [load, refreshKey]);
  useEffect(() => { ledgerApi.accounts().then(r => setAccounts(r.data)).catch(() => {}); }, []);

  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  const [alreadyRecorded, setAlreadyRecorded] = useState<string | null>(null);
  // The notice is about the entry exactly as it was compared; any change, edit or close drops it,
  // so "Add anyway" can't confirm an entry the server never checked.
  useEffect(() => { setAlreadyRecorded(null); }, [f, editId, open]);

  const close = () => { setF(blank); setOpen(false); setEditId(null); setEditing(null); setFormError(null); };
  const openAdd = () => { setF(blank); setEditId(null); setEditing(null); setFormError(null); setOpen(true); };

  const submit = async (confirmSeparate = false) => {
    if (!f.description.trim()) { setFormError('Add a description.'); return; }
    if (!(Number(f.amount) > 0)) { setFormError('Enter an amount above zero.'); return; }
    setSaving(true); setFormError(null); setNotice(null);
    try {
      const req = {
        description: f.description.trim(),
        amount: Number(f.amount),
        category: f.category,
        expenseDate: f.expenseDate || today(),
        // Blank on an edit clears the merchant; on a new entry the server derives one.
        merchant: f.merchant.trim() || (editId ? '' : undefined),
        paymentMethod: f.paymentMethod || undefined,
        cashAccountId: f.cashAccountId ? Number(f.cashAccountId) : undefined,
        note: f.note || undefined,
      };
      if (editId) {
        const categoryChanged = editing != null && editing.category !== f.category;
        const { data } = await expenseApi.update(editId, {
          ...req, rememberForMerchant: categoryChanged && !!f.merchant.trim() && f.rememberForMerchant,
        });
        if (data.alsoRecategorised) {
          setNotice(`${f.category} will be used for ${f.merchant.trim()} from now on; ${data.alsoRecategorised} earlier expense${data.alsoRecategorised === 1 ? '' : 's'} moved too.`);
        } else if (categoryChanged && f.rememberForMerchant && f.merchant.trim()) {
          setNotice(`${f.category} will be used for ${f.merchant.trim()} from now on.`);
        }
      } else {
        const { data } = await expenseApi.add({ ...req, confirmSeparate });
        if (data.alreadyRecorded) {
          // Already imported from email — nothing was added. Keep the form so the user can
          // still add it if it really is a second transaction.
          setAlreadyRecorded(`Already recorded from your email: ${data.description} on ${data.expenseDate}.`);
          return;
        }
      }
      setAlreadyRecorded(null);
      close();
      onChanged();
    } catch (e) {
      setFormError(errorText(e, "Couldn't save the expense. Try again."));
    } finally { setSaving(false); }
  };

  const startEdit = (e: ExpenseResponse) => {
    setEditId(e.id); setEditing(e); setOpen(true); setFormError(null);
    // A refund is stored negative; the form edits its size and the server keeps the sign.
    setF({ description: e.description, amount: String(Math.abs(e.amount)), category: e.category,
      expenseDate: e.expenseDate, merchant: e.merchant || '', paymentMethod: e.paymentMethod || '',
      cashAccountId: e.cashAccountId ? String(e.cashAccountId) : '', note: e.note || '', rememberForMerchant: true });
  };

  const del = async (e: ExpenseResponse) => {
    const name = e.merchant || e.description;
    const warn = isRefund(e) ? '' : ' Any refund recorded against it is removed too.';
    if (!window.confirm(`Delete "${name}" (${fmtINR(Math.abs(e.amount))})?${warn}`)) return;
    setActionError(null);
    try { await expenseApi.delete(e.id); onChanged(); }
    catch (err) { setActionError(errorText(err, `Couldn't delete "${name}".`)); }
  };

  const total = summary?.total ?? 0;
  const transfers = summary?.transfers ?? 0;
  const byCategory = summary?.byCategory ?? {};
  const catEntries = Object.entries(byCategory).filter(([, amt]) => amt !== 0).sort((a, b) => b[1] - a[1]);
  // Keep the row's own category selectable even when it isn't one a user picks (Investment).
  const categoryOptions = editing && !EXPENSE_CATEGORIES.includes(editing.category)
    ? [...EXPENSE_CATEGORIES, editing.category] : EXPENSE_CATEGORIES;
  const categoryChanged = editing != null && editing.category !== f.category;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bear"><ShoppingCart size={15} /></div>
          <h3 className="font-bold text-ink text-sm">Expenses</h3>
          {items.length > 0 && <span className="badge-bear">{items.length}</span>}
        </div>
        <div className="flex items-center gap-2">
          <button aria-label="Previous" onClick={onPrev} className="btn-icon"><ChevronLeft size={12} /></button>
          <span className="text-xs text-gray-400 w-28 text-center">{monthLabel}</span>
          <button aria-label="Next" onClick={onNext} className="btn-icon"><ChevronRight size={12} /></button>
          <button onClick={() => (open && !editId ? close() : openAdd())} className="btn-secondary text-xs flex items-center gap-1 ml-1"><Plus size={11} /> Add</button>
        </div>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          {editing && isRefund(editing) && (
            <p className="text-2xs text-gray-500">This is a refund: it reduces your spending by the amount below.</p>
          )}
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Description *</label>
              <input value={f.description} onChange={e => setF(x => ({ ...x, description: e.target.value }))}
                placeholder="Swiggy order" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">{editing && isRefund(editing) ? 'Refund amount (₹) *' : 'Amount (₹) *'}</label>
              <input type="number" min="0" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
                placeholder="500" className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Category</label>
              <select value={f.category} onChange={e => setF(x => ({ ...x, category: e.target.value }))} className="input-field text-xs">
                {categoryOptions.map(c => <option key={c} value={c}>{categoryLabel(c)}</option>)}
              </select>
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Date</label>
              <input type="date" value={f.expenseDate} onChange={e => setF(x => ({ ...x, expenseDate: e.target.value }))} className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Merchant</label>
              <input value={f.merchant} onChange={e => setF(x => ({ ...x, merchant: e.target.value }))}
                placeholder="Swiggy" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Payment method</label>
              <input value={f.paymentMethod} onChange={e => setF(x => ({ ...x, paymentMethod: e.target.value }))}
                placeholder="UPI, Card, Cash…" className="input-field text-xs" />
            </div>
          </div>
          <div>
            <label className="stat-label block mb-1 text-2xs">Paid from account</label>
            <select value={f.cashAccountId} onChange={e => setF(x => ({ ...x, cashAccountId: e.target.value }))} className="input-field text-xs w-full">
              <option value="">Unspecified</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.name}{a.bank ? ` (${a.bank})` : ''}</option>)}
            </select>
          </div>
          <input value={f.note} onChange={e => setF(x => ({ ...x, note: e.target.value }))}
            placeholder="Note (optional)" className="input-field text-xs w-full" />
          {categoryChanged && f.merchant.trim() && (
            <label className="flex items-center gap-2 text-2xs text-gray-400">
              <input type="checkbox" checked={f.rememberForMerchant} onChange={e => setF(x => ({ ...x, rememberForMerchant: e.target.checked }))} />
              Use {categoryLabel(f.category)} for all {f.merchant.trim()} expenses, past and future
            </label>
          )}
          {alreadyRecorded && (
            <div className="text-2xs text-gray-400 flex items-center justify-between gap-2">
              <span>{alreadyRecorded}</span>
              <button onClick={() => submit(true)} disabled={saving} className="text-ink underline shrink-0">Add anyway</button>
            </div>
          )}
          {formError && <p className="text-2xs text-bear">{formError}</p>}
          <div className="flex gap-2">
            <button onClick={() => submit()} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : editId ? 'Update Expense' : 'Save Expense'}</button>
            <button onClick={close} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {notice && (
        <div className="text-2xs text-gray-400 mb-2 flex items-center justify-between gap-2">
          <span>{notice}</span>
          <button aria-label="Dismiss" onClick={() => setNotice(null)} className="btn-icon p-0.5"><X size={10} /></button>
        </div>
      )}
      {actionError && <p className="text-2xs text-bear mb-2">{actionError}</p>}

      {(total !== 0 || transfers > 0) && !failed && (
        <div className="mb-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-2xs text-gray-500">Total spent</span>
            <span className="font-bold text-bear font-mono text-sm">{maskText(fmtINR(total))}</span>
          </div>
          {catEntries.length > 0 && (
            <div className="space-y-1">
              {catEntries.map(([cat, amt]) => {
                const pct = total > 0 ? Math.max(0, amt / total * 100) : 0;
                const color = categoryColor(cat);
                return (
                  <div key={cat}>
                    <div className="flex justify-between text-2xs mb-0.5">
                      <span className="text-gray-400">{cat}</span>
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
          {transfers > 0 && (
            <div className="flex items-center justify-between text-2xs text-gray-500 pt-1 border-t border-surface-border/40">
              <span title="Card-bill payments, transfers between your own accounts and investment debits. Listed below, not counted as spending.">
                Transfers / card bills / investments (not counted)
              </span>
              <span className="font-mono">{maskText(fmtINR(transfers))}</span>
            </div>
          )}
        </div>
      )}

      <div className="flex items-center gap-2 mb-2">
        <select value={filterCategory} onChange={e => setFilterCategory(e.target.value)} className="input-field text-2xs py-1 w-36" aria-label="Filter by category">
          <option value="">All categories</option>
          {[...EXPENSE_CATEGORIES, 'Investment'].map(c => <option key={c} value={c}>{categoryLabel(c)}</option>)}
        </select>
        <form className="flex-1 flex items-center gap-1" onSubmit={e => { e.preventDefault(); setAppliedQuery(query.trim()); }}>
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search merchant, note…" className="input-field text-2xs py-1 flex-1" />
          <button type="submit" aria-label="Search" className="btn-icon p-1"><Search size={11} /></button>
          {filtering && (
            <button type="button" aria-label="Clear filters" onClick={() => { setFilterCategory(''); setQuery(''); setAppliedQuery(''); }} className="btn-icon p-1"><X size={11} /></button>
          )}
        </form>
      </div>

      {loading && !items.length
        ? <div className="h-10 animate-pulse bg-surface-hover rounded" />
        : failed
        ? <LoadFailure what="your expenses" onRetry={load} />
        : !items.length
        ? <p className="text-gray-600 text-xs text-center py-4">{filtering ? `No expenses in ${monthLabel} match.` : `No expenses recorded for ${monthLabel}.`}</p>
        : (
          <div className="max-h-64 overflow-y-auto space-y-0 divide-y divide-surface-border/30">
            {items.map(e => {
              const displayName = e.merchant || e.description;
              const refund = isRefund(e);
              const notSpending = e.spending === false || NOT_SPENDING.has(e.category);
              const tag = refund ? 'Refund' : notSpending ? 'Not counted' : null;
              const subtitle = [categoryLabel(e.category), e.paymentMethod, e.expenseDate].filter(Boolean).join(' · ');
              const CatIcon = categoryIcon(e.category);
              const catColor = categoryColor(e.category);
              const amountClass = refund ? 'text-bull' : notSpending ? 'text-gray-500' : 'text-bear';
              return (
                <div key={e.id} className="flex items-center justify-between py-1.5 cursor-pointer hover:bg-surface-hover/50 rounded -mx-1 px-1 transition-colors"
                     onClick={() => setDetail(e)}>
                  <div className="min-w-0 flex-1 flex items-center gap-2">
                    <span className="w-6 h-6 rounded-full flex items-center justify-center shrink-0" style={{ backgroundColor: `${catColor}1A` }}>
                      <CatIcon size={12} style={{ color: catColor }} />
                    </span>
                    <div className="min-w-0 flex-1">
                      <span className="text-ink text-xs font-medium truncate block">
                        {displayName}
                        {tag && <span className="ml-1.5 text-2xs font-normal text-gray-500 border border-surface-border rounded px-1">{tag}</span>}
                      </span>
                      <div className="text-2xs text-gray-600 truncate">{subtitle}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-2">
                    <span className={`${amountClass} text-xs font-mono font-semibold`}>{refund ? '−' : ''}{maskText(fmtINR(Math.abs(e.amount)))}</span>
                    <AiProvenanceButton referenceId={e.sourceEmailId} record={{ kind: 'expense', id: e.id }} />
                    <button onClick={(ev) => { ev.stopPropagation(); startEdit(e); }} className="btn-icon text-gray-500 hover:text-ink p-0.5" title="Edit expense"><Edit2 size={11} /></button>
                    <button aria-label="Delete" onClick={(ev) => { ev.stopPropagation(); del(e); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
                  </div>
                </div>
              );
            })}
          </div>
        )
      }

      <MerchantRules refreshKey={refreshKey} onChanged={onChanged} />

      {detail && (
        <TransactionDetail
          open={!!detail}
          onClose={() => setDetail(null)}
          title={detail.merchant || detail.description}
          badgeColor={categoryColor(detail.category)}
          badgeLabel={categoryLabel(detail.category)}
          amount={detail.amount}
          fields={[
            { label: 'Merchant', value: detail.merchant },
            { label: 'Description', value: detail.description !== detail.merchant ? detail.description : undefined },
            { label: 'Category', value: categoryLabel(detail.category) },
            { label: 'Type', value: isRefund(detail) ? 'Refund of an earlier purchase' : detail.spending === false ? 'Not counted as spending' : undefined },
            { label: 'Date', value: detail.expenseDate },
            { label: 'Payment Method', value: detail.paymentMethod },
            { label: 'Account', value: accounts.find(a => a.id === detail.cashAccountId)?.name },
            { label: 'Source', value: detail.sourceEmailId ? 'Auto-imported from email' : 'Manual entry' },
            { label: 'Note', value: detail.note },
          ]}
        />
      )}
    </div>
  );
}

/** The merchants the user has given a category, with a way to stop applying one. */
function MerchantRules({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [rules, setRules] = useState<{ id: number; merchant: string; category: string }[]>([]);
  const [show, setShow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { expenseApi.merchantRules().then(r => setRules(r.data)).catch(() => {}); }, [refreshKey]);
  if (!rules.length) return null;
  const forget = async (id: number) => {
    setError(null);
    try { await expenseApi.forgetMerchantRule(id); setRules(rs => rs.filter(r => r.id !== id)); onChanged(); }
    catch (e) { setError(errorText(e, "Couldn't remove that rule.")); }
  };
  return (
    <div className="mt-2 pt-2 border-t border-surface-border/40">
      <button onClick={() => setShow(s => !s)} className="text-2xs text-gray-500 hover:text-ink">
        {show ? 'Hide' : 'Show'} remembered merchant categories ({rules.length})
      </button>
      {show && (
        <div className="mt-1 space-y-0.5">
          {rules.map(r => (
            <div key={r.id} className="flex items-center justify-between text-2xs">
              <span className="text-gray-400 truncate">{r.merchant} → {categoryLabel(r.category)}</span>
              <button onClick={() => forget(r.id)} className="text-gray-600 hover:text-bear shrink-0 ml-2" title="New expenses from this merchant go back to the automatic category; existing ones keep theirs">Stop</button>
            </div>
          ))}
          {error && <p className="text-2xs text-bear">{error}</p>}
        </div>
      )}
    </div>
  );
}
