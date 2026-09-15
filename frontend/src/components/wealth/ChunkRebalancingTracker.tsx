import { useEffect, useState, useCallback } from 'react';
import { Layers, ChevronDown, ChevronUp, Plus, Target, CheckCircle2 } from 'lucide-react';
import { redemptionApi } from '../../api/redemption';
import type { MfRedemption, DeploymentPlan } from '../../api/redemption';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtNum = (n: number | null | undefined, dp = 3) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: dp }).format(n);

/**
 * Chunk Rebalancing Tracker — for money redeemed from a fund and being redeployed in tranches
 * rather than all at once.
 *
 * Shows target vs executed vs pending per redemption, the average NAV actually achieved across
 * the chunks deployed so far, and the plan's remaining tranches with their trigger conditions.
 * Chunks are recorded manually: this tracks execution, it does not place trades.
 */
function RedemptionCard({ r, onChanged }: { r: MfRedemption; onChanged: () => void }) {
  const maskText = useMaskedText();
  const [open, setOpen] = useState(false);
  const [plan, setPlan] = useState<DeploymentPlan | null>(null);
  const [amount, setAmount] = useState('');
  const [targetFund, setTargetFund] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const deployed = r.reinvestedAmount ?? 0;
  const target = r.redeemedAmount ?? 0;
  const remaining = r.cashRemaining ?? Math.max(target - deployed, 0);
  const pct = target > 0 ? Math.min((deployed / target) * 100, 100) : 0;
  const chunks = r.reinvestments ?? [];

  // Average NAV achieved is only meaningful where the chunk recorded the units it bought;
  // the API tracks rupee amounts per chunk, so this is the average deployment size instead of
  // an invented NAV. Stated plainly rather than labelled as something it isn't.
  const avgChunk = chunks.length > 0 ? deployed / chunks.length : null;

  useEffect(() => {
    if (!open || plan) return;
    redemptionApi.getDeploymentPlan(r.id).then(res => setPlan(res.data)).catch(() => setPlan(null));
  }, [open, plan, r.id]);

  const addChunk = async () => {
    const amt = Number(amount);
    if (!Number.isFinite(amt) || amt <= 0) { setError('Enter an amount greater than zero.'); return; }
    if (amt > remaining) { setError(`Only ${fmt(remaining)} is left to deploy from this redemption.`); return; }
    setSaving(true); setError('');
    try {
      await redemptionApi.recordReinvestment(r.id, { amount: amt, targetFund: targetFund.trim() || undefined });
      setAmount(''); setTargetFund('');
      onChanged();
    } catch {
      setError('Could not record that chunk — please try again.');
    } finally { setSaving(false); }
  };

  const complete = r.status === 'COMPLETED' || remaining <= 0;

  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3">
      <div className="flex items-start justify-between gap-3 mb-2.5 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-sm text-ink font-semibold truncate">{r.fundName ?? r.symbol}</span>
            <span className={r.gainType === 'LTCG' ? 'pill-bull' : 'pill-neutral'}>{r.gainType}</span>
            {complete
              ? <span className="pill-bull"><CheckCircle2 size={11} /> Fully deployed</span>
              : <span className="pill-info">Redeployment open</span>}
          </div>
          <div className="text-2xs text-gray-500 mt-0.5">
            Redeemed <span className="font-mono tabular-nums">{fmtNum(r.unitsRedeemed)}</span> units
            @ NAV <span className="font-mono tabular-nums">{fmtNum(r.navAtRedemption, 2)}</span> on {r.redemptionDate}
          </div>
        </div>
        <button onClick={() => setOpen(o => !o)} className="btn-ghost text-2xs shrink-0">
          {open ? <>Hide plan <ChevronUp size={11} /></> : <>Deployment plan <ChevronDown size={11} /></>}
        </button>
      </div>

      {/* Target → executed → pending */}
      <div className="grid grid-cols-3 gap-2 mb-2.5">
        <div>
          <div className="stat-label mb-0.5">Target</div>
          <div className="text-xs font-mono tabular-nums text-gray-200">{maskText(fmt(target))}</div>
        </div>
        <div>
          <div className="stat-label mb-0.5">Executed</div>
          <div className="text-xs font-mono tabular-nums text-bull">{maskText(fmt(deployed))}</div>
        </div>
        <div>
          <div className="stat-label mb-0.5">Pending</div>
          <div className={`text-xs font-mono tabular-nums ${remaining > 0 ? 'text-neutral' : 'text-gray-500'}`}>
            {maskText(fmt(remaining))}
          </div>
        </div>
      </div>

      <div className="w-full h-2 rounded-full bg-surface-card overflow-hidden mb-1.5">
        <div className="h-full rounded-full bg-bull transition-all" style={{ width: `${pct}%` }} />
      </div>
      <div className="flex items-center justify-between text-2xs text-gray-500 mb-2.5">
        <span><span className="font-mono tabular-nums">{pct.toFixed(0)}%</span> redeployed</span>
        <span>
          <span className="font-mono tabular-nums">{chunks.length}</span> chunk{chunks.length === 1 ? '' : 's'}
          {avgChunk != null && <> · avg <span className="font-mono tabular-nums">{maskText(fmt(avgChunk))}</span></>}
        </span>
      </div>

      {/* Executed chunks */}
      {chunks.length > 0 && (
        <div className="space-y-1 mb-2.5 border-t border-surface-border pt-2">
          {chunks.map(c => (
            <div key={c.id} className="flex items-center justify-between text-2xs">
              <span className="text-gray-400 truncate min-w-0">
                {c.date}{c.targetFund && <span className="text-gray-300"> → {c.targetFund}</span>}
              </span>
              <span className="font-mono tabular-nums text-gray-300 shrink-0 ml-2">{maskText(fmt(c.amount))}</span>
            </div>
          ))}
        </div>
      )}

      {/* Remaining plan */}
      {open && (
        <div className="border-t border-surface-border pt-2.5 mb-2.5">
          {plan ? (
            <>
              <div className="stat-label mb-1.5 flex items-center gap-1.5"><Target size={11} /> Suggested tranches</div>
              <div className="space-y-1.5">
                {plan.tranches.map((t, i) => (
                  <div key={i} className="flex items-start justify-between gap-2 text-2xs">
                    <div className="min-w-0">
                      <span className="text-gray-300">{t.label}</span>
                      <span className="text-gray-600 ml-1 font-mono tabular-nums">({t.percentOfTotal.toFixed(0)}%)</span>
                      <div className="text-gray-600">{t.trigger}</div>
                    </div>
                    <span className="font-mono tabular-nums text-gray-300 shrink-0">{maskText(fmt(t.amount))}</span>
                  </div>
                ))}
              </div>
              {plan.suitableFunds?.length > 0 && (
                <p className="text-2xs text-gray-500 mt-2">Candidates: {plan.suitableFunds.join(', ')}</p>
              )}
              <p className="text-2xs text-gray-700 mt-1.5">{plan.basis}</p>
            </>
          ) : (
            <p className="text-2xs text-gray-600">Deployment plan unavailable.</p>
          )}
        </div>
      )}

      {/* Record a chunk — manual confirmation only */}
      {!complete && (
        <div className="border-t border-surface-border pt-2.5">
          <div className="flex items-center gap-1.5 flex-wrap">
            <input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric"
                   placeholder="Amount deployed"
                   className="input-field text-2xs py-1 font-mono tabular-nums flex-1 min-w-[120px]" style={{ minHeight: 28 }} />
            <input value={targetFund} onChange={e => setTargetFund(e.target.value)}
                   placeholder="Into which fund"
                   className="input-field text-2xs py-1 flex-1 min-w-[120px]" style={{ minHeight: 28 }} />
            <button onClick={addChunk} disabled={saving}
                    className="btn-secondary text-2xs py-1 px-2 shrink-0">
              <Plus size={11} /> {saving ? 'Saving…' : 'Record chunk'}
            </button>
          </div>
          {error && <p className="text-2xs text-bear mt-1.5">{error}</p>}
        </div>
      )}
    </div>
  );
}

export function ChunkRebalancingTracker() {
  const maskText = useMaskedText();
  const [redemptions, setRedemptions] = useState<MfRedemption[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    redemptionApi.list()
      .then(r => setRedemptions(r.data))
      .catch(() => setRedemptions([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const open = redemptions.filter(r => r.status === 'ACTIVE' && (r.cashRemaining ?? 0) > 0);
  const done = redemptions.filter(r => !open.includes(r));
  const awaiting = open.reduce((s, r) => s + (r.cashRemaining ?? 0), 0);

  if (loading) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
        <h3 className="section-title mb-0"><Layers size={15} className="text-brand-light" /> Chunk Rebalancing Tracker</h3>
        {awaiting > 0 && (
          <span className="pill-neutral">
            <span className="font-mono tabular-nums">{maskText(fmt(awaiting))}</span> awaiting redeployment
          </span>
        )}
      </div>

      {awaiting > 0 && (
        <p className="text-2xs text-gray-600 mb-3">
          This is money already sitting in your bank from a redemption — it's part of your cash
          balance, not additional funds.
        </p>
      )}

      {redemptions.length === 0 ? (
        <p className="text-xs text-gray-600 py-4">
          No fund redemptions recorded. When you redeem a fund, the proceeds appear here so the
          redeployment can be tracked chunk by chunk.
        </p>
      ) : (
        <div className="space-y-2.5">
          {open.map(r => <RedemptionCard key={r.id} r={r} onChanged={load} />)}
          {done.map(r => <RedemptionCard key={r.id} r={r} onChanged={load} />)}
        </div>
      )}
    </div>
  );
}
