import { useState, useEffect, useCallback } from 'react';
import { TrendingUp, ChevronLeft, ChevronRight, Plus, Edit2, Trash2, Wallet } from 'lucide-react';
import { incomeApi, INCOME_SOURCES } from '../../api/income';
import type { IncomeResponse, IncomeSummary } from '../../api/income';
import { useMaskedText } from '../shared/Amount';
import { TransactionDetail } from '../shared/TransactionDetail';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const today = () => new Date().toISOString().slice(0, 10);

const SRC_COLORS: Record<string, string> = {
  Salary: '#00c47a', Freelance: '#2563eb', Dividend: '#e8a020',
  Interest: '#f59e0b', Rental: '#8b5cf6', Business: '#00b8d9', Other: '#6b7280',
};

export function IncomeSection() {
  const maskText = useMaskedText();
  const now = new Date();
  const [year, setYear]   = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [items, setItems] = useState<IncomeResponse[]>([]);
  const [summary, setSummary] = useState<IncomeSummary | null>(null);
  const [open, setOpen]   = useState(false);
  const [saving, setSaving] = useState(false);

  const blank = { description: '', amount: '', source: 'Salary', incomeDate: today(), note: '' };
  const [f, setF] = useState(blank);
  const [editId, setEditId] = useState<number | null>(null);
  const [detail, setDetail] = useState<IncomeResponse | null>(null);

  const load = useCallback(async () => {
    try {
      const [listRes, sumRes] = await Promise.all([
        incomeApi.list(year, month),
        incomeApi.summary(year, month),
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
        source: f.source,
        incomeDate: f.incomeDate || today(),
        note: f.note || undefined,
      };
      if (editId) await incomeApi.update(editId, req); else await incomeApi.add(req);
      setF(blank); setOpen(false); setEditId(null);
      await load();
    } catch {} finally { setSaving(false); }
  };

  const startEdit = (e: IncomeResponse) => {
    setEditId(e.id); setOpen(true);
    setF({ description: e.description, amount: String(e.amount), source: e.source,
      incomeDate: e.incomeDate, note: e.note || '' });
  };

  const del = async (id: number) => {
    try { await incomeApi.delete(id); await load(); } catch {}
  };

  const total = summary?.total ?? 0;
  const bySource = summary?.bySource ?? {};
  const srcEntries = Object.entries(bySource).sort((a, b) => b[1] - a[1]);

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-bull"><TrendingUp size={15} /></div>
          <h3 className="font-bold text-white text-sm">Income</h3>
          {items.length > 0 && <span className="badge-bull">{items.length}</span>}
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
                placeholder="July salary" className="input-field text-xs" />
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Amount (₹) *</label>
              <input type="number" value={f.amount} onChange={e => setF(x => ({ ...x, amount: e.target.value }))}
                placeholder="80000" className="input-field text-xs" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="stat-label block mb-1 text-2xs">Source</label>
              <select value={f.source} onChange={e => setF(x => ({ ...x, source: e.target.value }))} className="input-field text-xs">
                {INCOME_SOURCES.map(s => <option key={s}>{s}</option>)}
              </select>
            </div>
            <div>
              <label className="stat-label block mb-1 text-2xs">Date</label>
              <input type="date" value={f.incomeDate} onChange={e => setF(x => ({ ...x, incomeDate: e.target.value }))} className="input-field text-xs" />
            </div>
          </div>
          <input value={f.note} onChange={e => setF(x => ({ ...x, note: e.target.value }))}
            placeholder="Note (optional)" className="input-field text-xs w-full" />
          <div className="flex gap-2">
            <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Save Income'}</button>
            <button onClick={() => { setF(blank); setOpen(false); }} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
          </div>
        </div>
      )}

      {total > 0 && (
        <div className="mb-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-2xs text-gray-500">Total earned</span>
            <span className="font-bold text-bull font-mono text-sm">{maskText(fmtINR(total))}</span>
          </div>
          {srcEntries.length > 0 && (
            <div className="space-y-1">
              {srcEntries.map(([src, amt]) => {
                const pct = total > 0 ? (amt / total * 100) : 0;
                const color = SRC_COLORS[src] ?? '#6b7280';
                return (
                  <div key={src}>
                    <div className="flex justify-between text-2xs mb-0.5">
                      <span className="text-gray-400">{src}</span>
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
                      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: SRC_COLORS[e.source] ?? '#6b7280' }} />
                      <span className="text-white text-xs font-medium truncate">{displayName}</span>
                    </div>
                    <div className="text-2xs text-gray-600 ml-3 truncate">{subtitle}</div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-2">
                    <span className="text-bull text-xs font-mono font-semibold">{maskText(fmtINR(e.amount))}</span>
                    <button onClick={(ev) => { ev.stopPropagation(); startEdit(e); }} className="btn-icon text-gray-500 hover:text-white p-0.5" title="Edit income"><Edit2 size={11} /></button>
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
          title={detail.payer || detail.description}
          badgeColor="#00c47a"
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

export function SavingsRatioCard() {
  const maskText = useMaskedText();
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const [income, setIncome] = useState(0);
  const [expense, setExpense] = useState(0);

  useEffect(() => {
    Promise.all([
      incomeApi.summary(year, month),
      import('../../api/expense').then(m => m.expenseApi.summary(year, month)),
    ]).then(([inc, exp]) => {
      setIncome(inc.data?.total ?? 0);
      setExpense(exp.data?.total ?? 0);
    }).catch(() => {});
  }, []);

  const saved = income - expense;
  const ratio = income > 0 ? (saved / income * 100) : 0;
  const monthLabel = new Date(year, month - 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });

  if (income === 0 && expense === 0) return null;

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-3">
        <div className="icon-badge-brand"><Wallet size={15} /></div>
        <h3 className="font-bold text-white text-sm">Savings — {monthLabel}</h3>
      </div>
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
          <span className={ratio >= 20 ? 'text-bull' : ratio >= 10 ? 'text-yellow-400' : 'text-bear'}>{maskText(`${ratio.toFixed(1)}%`)}</span>
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
