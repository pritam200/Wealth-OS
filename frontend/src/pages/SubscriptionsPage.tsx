import { useEffect, useState, useCallback } from 'react';
import { RefreshCw, Repeat, TrendingUp } from 'lucide-react';
import { subscriptionsApi } from '../api/subscriptions';
import type { Subscription } from '../api/subscriptions';
import { Amount } from '../components/shared/Amount';
import { LoadFailure } from '../components/shared/LoadFailure';

const CADENCE_LABEL: Record<Subscription['cadence'], string> = {
  WEEKLY: '/week',
  MONTHLY: '/month',
  ANNUAL: '/year',
};

function money(v: number | undefined) {
  if (v == null) return '—';
  return '₹' + v.toLocaleString('en-IN', { maximumFractionDigits: 0 });
}

function SubscriptionRow({ sub }: { sub: Subscription }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-surface-border bg-surface-hover/40 p-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-ink truncate">{sub.merchant}</span>
          {sub.priceIncreased && (
            <span className="inline-flex items-center gap-1 rounded-full bg-neutral/15 px-2 py-0.5 text-2xs font-semibold text-neutral">
              <TrendingUp size={10} /> Price increased
            </span>
          )}
        </div>
        <div className="stat-label mt-1">
          {sub.category ?? 'Uncategorized'} · last charged {new Date(sub.lastSeenDate).toLocaleDateString('en-IN')}
          {' · '}{sub.occurrenceCount} charges seen
        </div>
      </div>
      <div className="text-right shrink-0">
        {sub.priceIncreased ? (
          <div className="text-sm font-mono tabular-nums">
            <span className="text-gray-500 line-through"><Amount value={money(sub.previousAmount)} /></span>
            {' → '}
            <span className="text-neutral font-bold"><Amount value={money(sub.currentAmount)} /></span>
          </div>
        ) : (
          <div className="text-sm font-mono tabular-nums font-bold text-ink"><Amount value={money(sub.currentAmount)} /></div>
        )}
        <div className="stat-label">{CADENCE_LABEL[sub.cadence]}</div>
      </div>
    </div>
  );
}

/**
 * Recurring charges detected from expense history — a dedicated list view alongside the
 * Today's Actions nudges for the same underlying signal (see SubscriptionDetectionService).
 * Read-only: there is no per-subscription entity to edit here.
 */
export function SubscriptionsPage() {
  const [subs, setSubs] = useState<Subscription[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setFailed(false);
    subscriptionsApi.list()
      .then(res => setSubs(res.data))
      // A failed load is not "no subscriptions" — say it failed.
      .catch(() => { setSubs(null); setFailed(true); })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Repeat size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Subscriptions</h2>
          <p className="text-gray-500 text-xs">Recurring charges found in your expense history</p>
        </div>
      </div>

      <div className="card-elevated">
        <div className="flex items-center justify-between mb-3">
          <h3 className="section-title mb-0"><Repeat size={15} className="text-brand-light" /> Detected Subscriptions</h3>
          <button onClick={load} className="btn-icon" title="Refresh"><RefreshCw size={12} /></button>
        </div>

        {loading ? (
          <div className="h-24 rounded-xl bg-surface-hover animate-pulse" />
        ) : failed ? (
          <LoadFailure what="your subscriptions" onRetry={load} />
        ) : !subs || subs.length === 0 ? (
          <p className="text-xs text-gray-600">
            No recurring charges detected yet — a merchant needs at least three charges on a
            consistent weekly/monthly/annual cadence and a stable amount before it's flagged here.
          </p>
        ) : (
          <div className="space-y-2">
            {subs.map(sub => <SubscriptionRow key={sub.merchant + sub.cadence} sub={sub} />)}
          </div>
        )}

        <p className="text-2xs text-gray-600 mt-3">
          Detected from pattern-matching your categorized expense history, not from any
          subscription-management API — a price change is flagged for you to review, never
          acted on automatically.
        </p>
      </div>
    </div>
  );
}
