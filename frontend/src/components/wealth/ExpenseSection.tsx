import { useState, useEffect, useCallback } from 'react';
import { ShoppingCart, Plus, Edit2, Trash2, ChevronLeft, ChevronRight } from 'lucide-react';
import { expenseApi, EXPENSE_CATEGORIES } from '../../api/expense';
import type { ExpenseResponse, ExpenseSummary } from '../../api/expense';
import { useMaskedText } from '../shared/Amount';
import { TransactionDetail } from '../shared/TransactionDetail';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

const CAT_COLORS: Record<string, string> = {
  Food: '#e8a020', Travel: '#2563eb', Utilities: '#8b5cf6',
  Entertainment: '#f59e0b', Health: '#00c47a', Shopping: '#f03e3e',
  EMI: '#ef4444', Investment: '#00b8d9', Other: '#6b7280',
};

export function ExpenseSection() {
  const maskText = useMaskedText();
  const now = new Date();
  const [year, setYear]   = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [items, setItems] = useState<ExpenseResponse[]>([]);
  const [summary, setSummary] = useState<ExpenseSummary | null>(null);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = { description: '', amount: '', category: 'Food', expenseDate: today(), note: '' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ExpenseResponse | null>(null);

  const load = useCallback(async () => {
    try {
      const [listRes, sumRes] = await Promise.all([
        expenseApi.list(year, month),
        expenseApi.summary(year, month),
      ]);
      setItems(listRes.data);
      setSummary(sumRes.data);
    } catch {}
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const prevMonth = () => { if (month === 1) { setYear(y => y - 1); setMonth(12); } else setMonth(m => m - 1); };
  const nextMonth = () => { if (month === 12) { setYear(y => y + 1); setMonth(1); } else setMonth(m => m + 1); };
  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  const submit = async () => {
    if (!f.description || !f.amount) return;
    setSaving(true);
    try {
      const req = {
        description: f.description,
        amount: Number(f.amount),
        category: f.category,
        expenseDate: f.expenseDate || today(),
        note: f.note || undefined,
      };
      if (editId) await expenseApi.update(editId, req); else await expenseApi.add(req);
      setF(blank); setOpen(false); setEditId(null);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (e: ExpenseResponse) => {
    setEditId(e.id); setOpen(true);
    setF({ description: e.description, amount: String(e.amount), category: e.category,
      expenseDate: e.expenseDate, note: e.note || '' });
  };

  const del = async (id: number) => {
    try { await expenseApi.delete(id); await load(); } catch {}
  };

  const total = summary?.total ?? 0;
  const byCategory = summary?.byCategory ?? {};
  const catEntries = Object.entries(byCategory).sort((a, b) => b[1] - a[1]);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bear"><ShoppingCart size={15} /></div>
          <h3 className="font-bold text-white text-sm">Expenses</h3>
          {items.length > 0 && <span className="badge-bear">{items.length}</span>}
        </div>
        <div className="flex items-center gap-2">
          <button onClick={prevMonth} className="btn-icon"><ChevronLeft size={12} /></button>
          <span className="text-xs text-gray-400 w-28 text-center">{monthLabel}</span>
          <button onClick={nextMonth} className="btn-icon"><ChevronRight size={12} /></button>
          <button onClick={() => setOpen(o => !o)} className="btn-secondary text-xs flex items-center gap-1 ml-1"><Plus size={11} /> Add</button>
        </div>
      </div>

      {open && (
        <div className="bg-surface-hover rounded-lg p-3 mb-3 space-y-2 border border-surface-border">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Description *</label>
              <input value={f.description} onChange={e => setF(x => ({ ...x, description: e.target.value }))}
                placeholder="Swiggy order" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Amount (₹) *</label>
              <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
                placeholder="500" className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Category</label>
              <select value={f.category} onChange={e => setF(x => ({ ...x, category: e.target.value }))} className="input-field text-xs">
                {EXPENSE_CATEGORIES.map(c => <option key={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Date</label>
              <input type="date" value={f.expenseDate} onChange={e => setF(x => ({ ...x, expenseDate: e.target.value }))} className="input-field text-xs" />
            </div>
          </div>
          <input value={f.note} onChange={e => setF(x => ({ ...x, note: e.target.value }))}
            placeholder="Note (optional)" className="input-field text-xs w-full" />
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Expense'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {total > 0 && (
        <div className="mb-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-2xs text-gray-500">Total spent</span>
            <span className="font-bold text-bear font-mono text-sm">{maskText(fmtINR(total))}</span>
          </div>
          {catEntries.length > 0 && (
            <div className="space-y-1">
              {catEntries.map(([cat, amt]) => {
                const pct = total > 0 ? (amt / total * 100) : 0;
                const color = CAT_COLORS[cat] ?? '#6b7280';
                return (
                  <div key={cat}>
                    <div className="flex justify-between text-2xs mb-0.5">
                      <span className="text-gray-400">{cat}</span>
                      <span className="text-white font-mono">{maskText(fmtINR(amt))} <span className="text-gray-600">({maskText(`${pct.toFixed(0)}%`)})</span></span>
                    </div>
                    <div className="h-1 bg-surface-hover rounded-full overflow-hidden">
                      <div className="h-full rounded-full" style={{ width: `${pct}%`, backgroundColor: color }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {!items.length
        ? <p className="text-gray-600 text-xs text-center py-4">No expenses recorded for {monthLabel}.</p>
        : (
          <div className="max-h-64 overflow-y-auto space-y-0 divide-y divide-surface-border/30">
            {items.map(e => {
              const displayName = e.merchant || e.description;
              const subtitle = [e.category, e.paymentMethod, e.expenseDate].filter(Boolean).join(' · ');
              return (
                <div key={e.id} className="flex items-center justify-between py-1.5 cursor-pointer hover:bg-surface-hover/50 rounded -mx-1 px-1 transition-colors"
                     onClick={() => setDetail(e)}>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: CAT_COLORS[e.category] ?? '#6b7280' }} />
                      <span className="text-white text-xs font-medium truncate">{displayName}</span>
                    </div>
                    <div className="text-2xs text-gray-600 ml-3 truncate">{subtitle}</div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-2">
                    <span className="text-bear text-xs font-mono font-semibold">{maskText(fmtINR(e.amount))}</span>
                    <button onClick={(ev) => { ev.stopPropagation(); startEdit(e); }} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit expense"><Edit2 size={11} /></button>
                    <button onClick={(ev) => { ev.stopPropagation(); del(e.id); }} className="btn-icon text-gray-700 hover:text-bear p-0.5"><Trash2 size={11} /></button>
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
          title={detail.merchant || detail.description}
          badgeColor="#f03e3e"
          badgeLabel={detail.category}
          amount={detail.amount}
          fields={[
            { label: 'Merchant', value: detail.merchant },
            { label: 'Description', value: detail.description !== detail.merchant ? detail.description : undefined },
            { label: 'Category', value: detail.category },
            { label: 'Date', value: detail.expenseDate },
            { label: 'Payment Method', value: detail.paymentMethod },
            { label: 'Source', value: detail.sourceEmailId ? 'Auto-imported from email' : 'Manual entry' },
            { label: 'Note', value: detail.note },
          ]}
        />
      )}
    </div>
  );
}
