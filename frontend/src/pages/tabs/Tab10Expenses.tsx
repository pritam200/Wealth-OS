import { useState, useEffect, useCallback } from 'react';
import { AlertTriangle, Trash2, Loader2, Receipt } from 'lucide-react';
import { ExpenseSection } from '../../components/wealth/ExpenseSection';
import { IncomeSection, SavingsRatioCard } from '../../components/wealth/IncomeSection';
import { expenseApi } from '../../api/expense';
import type { ExpenseResponse } from '../../api/expense';
import { useMaskedText } from '../../components/shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

// Surfaces + one-click removes stray Expense rows imported before the Gmail parser fix
// that stops SIP/mutual-fund/broker debits from ever being booked as an Expense.
function MiscategorizedInvestmentsBanner() {
  const [rows, setRows] = useState<ExpenseResponse[] | null>(null);
  const [purging, setPurging] = useState(false);

  const load = useCallback(async () => {
    try { const { data } = await expenseApi.listMiscategorizedInvestments(); setRows(data); } catch {}
  }, []);

  useEffect(() => { load(); }, [load]);

  const purge = async () => {
    setPurging(true);
    try { await expenseApi.purgeMiscategorizedInvestments(); await load(); } catch {} finally { setPurging(false); }
  };

  const maskText = useMaskedText();
  if (!rows || rows.length === 0) return null;
  const total = rows.reduce((s, r) => s + (r.amount || 0), 0);

  return (
    <div className="card border border-yellow-400/30 bg-yellow-400/5">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex items-start gap-2">
          <AlertTriangle size={16} className="text-yellow-400 shrink-0 mt-0.5" />
          <div>
            <p className="text-sm text-white font-medium">{rows.length} investment-related entries were mis-imported as expenses</p>
            <p className="text-xs text-gray-500 mt-0.5">
              {maskText(fmtINR(total))} total — SIP/mutual fund/broker debits that were incorrectly booked here before a Gmail-sync fix.
              These aren't real spending. Removing them won't affect your portfolio; re-run "Sync now" to import them correctly there.
            </p>
          </div>
        </div>
        <button onClick={purge} disabled={purging}
          className="btn-ghost text-xs border border-yellow-400/40 text-yellow-400 hover:bg-yellow-400/10 flex items-center gap-1.5 px-3 py-1.5 shrink-0">
          {purging ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
          {purging ? 'Removing…' : `Remove all ${rows.length}`}
        </button>
      </div>
    </div>
  );
}

export function Tab10Expenses() {
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#00c2ff] to-[#6d5efc] flex items-center justify-center text-white shadow-lift shrink-0">
          <Receipt size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Income &amp; Expenses</h2>
          <p className="text-gray-500 text-xs">Your monthly cash flow — what comes in, what goes out, and how much you save</p>
        </div>
      </div>

      <MiscategorizedInvestmentsBanner />

      <SavingsRatioCard />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <IncomeSection />
        <ExpenseSection />
      </div>
    </div>
  );
}
