import { useState, useEffect, useCallback } from 'react';
import { AlertTriangle, Trash2, Loader2, Receipt } from 'lucide-react';
import { ExpenseSection } from '../../components/wealth/ExpenseSection';
import { IncomeSection, SavingsRatioCard } from '../../components/wealth/IncomeSection';
import { MerchantSpendSection } from '../../components/wealth/MerchantSpendSection';
import { RentCard } from '../../components/wealth/RentCard';
import { MonthlyInvestmentPlanCard } from '../../components/wealth/MonthlyInvestmentPlanCard';
import { ScheduledInvestmentsCard } from '../../components/wealth/ScheduledInvestmentsCard';
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
  const [purgeError, setPurgeError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { const { data } = await expenseApi.listMiscategorizedInvestments(); setRows(data); } catch {}
  }, []);

  useEffect(() => { load(); }, [load]);

  const purge = async () => {
    if (!window.confirm(`Remove ${rows?.length ?? 0} investment entries from Expenses?`)) return;
    setPurging(true); setPurgeError(null);
    try { await expenseApi.purgeMiscategorizedInvestments(); await load(); }
    catch { setPurgeError("Couldn't remove them. Try again."); }
    finally { setPurging(false); }
  };

  const maskText = useMaskedText();
  if (!rows || rows.length === 0) return null;
  const total = rows.reduce((s, r) => s + (r.amount || 0), 0);

  return (
    <div className="card border-neutral/30">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex items-start gap-2">
          <AlertTriangle size={16} className="text-neutral shrink-0 mt-0.5" />
          <div>
            <p className="text-sm text-ink font-medium">{rows.length} investment-related entries were mis-imported as expenses</p>
            <p className="text-xs text-gray-500 mt-0.5">
              {maskText(fmtINR(total))} total — SIP/mutual fund/broker debits that were incorrectly booked here before a Gmail-sync fix.
              These aren't counted as spending. Removing them won't affect your portfolio; re-run "Sync now" to import them correctly there.
            </p>
            {purgeError && <p className="text-xs text-bear mt-1">{purgeError}</p>}
          </div>
        </div>
        <button onClick={purge} disabled={purging}
          className="btn-ghost text-xs border border-neutral/40 text-neutral hover:bg-neutral/10 flex items-center gap-1.5 px-3 py-1.5 shrink-0">
          {purging ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
          {purging ? 'Removing…' : `Remove all ${rows.length}`}
        </button>
      </div>
    </div>
  );
}

export function Tab10Expenses() {
  // One month and one refresh signal for every card, so they agree after a change.
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [refreshKey, setRefreshKey] = useState(0);
  const onChanged = useCallback(() => setRefreshKey(k => k + 1), []);
  const onPrev = () => { if (month === 1) { setYear(y => y - 1); setMonth(12); } else setMonth(m => m - 1); };
  const onNext = () => { if (month === 12) { setYear(y => y + 1); setMonth(1); } else setMonth(m => m + 1); };
  const shared = { year, month, onPrev, onNext, refreshKey, onChanged };
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Receipt size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Income &amp; Expenses</h2>
          <p className="text-gray-500 text-xs">Your monthly cash flow — what comes in, what goes out, and how much you save</p>
        </div>
      </div>

      <MiscategorizedInvestmentsBanner />

      <SavingsRatioCard year={year} month={month} refreshKey={refreshKey} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <IncomeSection {...shared} />
        <ExpenseSection {...shared} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <MonthlyInvestmentPlanCard />
        <ScheduledInvestmentsCard />
      </div>

      <RentCard />

      <MerchantSpendSection year={year} month={month} refreshKey={refreshKey} />
    </div>
  );
}
