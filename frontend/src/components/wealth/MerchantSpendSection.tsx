import { useEffect, useState } from 'react';
import { Store } from 'lucide-react';
import { expenseApi } from '../../api/expense';
import type { ExpenseResponse } from '../../api/expense';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

interface MerchantTotal {
  merchant: string;
  total: number;
  count: number;
  normalisedFrom: Set<string>;
}

/**
 * Spend grouped by normalised merchant.
 *
 * Raw bank narrations for one merchant vary constantly ("SWIGGY*BLR", "UPI-SWIGGY-1234"), so
 * grouping on the raw description splits one merchant's spend across many rows. Both the
 * email-import path and manual entry now normalise the merchant name, and this view groups on
 * that — falling back to the description only when no merchant could be derived.
 */
export function MerchantSpendSection() {
  const maskText = useMaskedText();
  const [rows, setRows] = useState<ExpenseResponse[] | null>(null);

  useEffect(() => {
    const now = new Date();
    expenseApi.list(now.getFullYear(), now.getMonth() + 1)
      .then(r => setRows(r.data))
      .catch(() => setRows([]));
  }, []);

  if (rows == null) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  const byMerchant = new Map<string, MerchantTotal>();
  for (const e of rows) {
    const label = (e.merchant || e.description || 'Unlabelled').trim();
    const key = label.toLowerCase();
    const existing = byMerchant.get(key);
    if (existing) {
      existing.total += e.amount ?? 0;
      existing.count += 1;
      if (e.description && e.description !== label) existing.normalisedFrom.add(e.description);
    } else {
      byMerchant.set(key, {
        merchant: label,
        total: e.amount ?? 0,
        count: 1,
        normalisedFrom: new Set(e.description && e.description !== label ? [e.description] : []),
      });
    }
  }

  const sorted = [...byMerchant.values()].sort((a, b) => b.total - a.total);
  const grandTotal = sorted.reduce((s, m) => s + m.total, 0);
  const top = sorted.slice(0, 12);

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
        <h3 className="section-title mb-0"><Store size={15} className="text-brand-light" /> Where It Goes</h3>
        <span className="text-2xs text-gray-500">
          <span className="font-mono tabular-nums">{sorted.length}</span> merchants this month
        </span>
      </div>

      {sorted.length === 0 ? (
        <p className="text-xs text-gray-600 py-3">No expenses recorded this month.</p>
      ) : (
        <div className="space-y-2">
          {top.map(m => {
            const share = grandTotal > 0 ? (m.total / grandTotal) * 100 : 0;
            return (
              <div key={m.merchant}>
                <div className="flex items-center justify-between gap-2 mb-1">
                  <span className="text-xs text-gray-300 truncate min-w-0"
                        title={m.normalisedFrom.size > 0
                          ? `Grouped from: ${[...m.normalisedFrom].slice(0, 4).join(', ')}`
                          : m.merchant}>
                    {m.merchant}
                    {m.count > 1 && (
                      <span className="text-2xs text-gray-600 ml-1.5 font-mono tabular-nums">×{m.count}</span>
                    )}
                  </span>
                  <span className="flex items-center gap-2 shrink-0">
                    <span className="text-2xs text-gray-600 font-mono tabular-nums">{share.toFixed(0)}%</span>
                    <span className="text-xs font-mono tabular-nums text-gray-200">{maskText(fmt(m.total))}</span>
                  </span>
                </div>
                <div className="w-full h-1 rounded-full bg-surface-hover overflow-hidden">
                  <div className="h-full rounded-full bg-brand" style={{ width: `${share}%` }} />
                </div>
              </div>
            );
          })}

          {sorted.length > top.length && (
            <p className="text-2xs text-gray-600 pt-1">
              +{sorted.length - top.length} smaller merchants totalling{' '}
              <span className="font-mono tabular-nums">
                {maskText(fmt(sorted.slice(12).reduce((s, m) => s + m.total, 0)))}
              </span>
            </p>
          )}
        </div>
      )}
    </div>
  );
}
