import { useState, useEffect, useCallback } from 'react';
import { Landmark, ChevronDown, ChevronUp, Loader2, Sparkles } from 'lucide-react';
import { redemptionApi } from '../../api/redemption';
import type { MfRedemption, DeploymentPlan } from '../../api/redemption';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

function DeploymentPlanCard({ redemptionId }: { redemptionId: number }) {
  const maskText = useMaskedText();
  const [plan, setPlan] = useState<DeploymentPlan | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    redemptionApi.getDeploymentPlan(redemptionId).then(r => setPlan(r.data)).catch(() => {}).finally(() => setLoading(false));
  }, [redemptionId]);

  if (loading) return <div className="flex items-center gap-2 text-xs text-gray-500 py-2"><Loader2 size={12} className="animate-spin" /> Building deployment plan…</div>;
  if (!plan) return null;

  return (
    <div className="mt-2 bg-surface-hover rounded-lg p-2.5">
      <div className="flex items-center gap-1.5 text-2xs text-brand mb-2"><Sparkles size={11} /> Reinvestment plan</div>
      <div className="space-y-1.5">
        {plan.tranches.map((t, i) => (
          <div key={i} className="flex items-start justify-between gap-2 text-2xs">
            <div>
              <span className="text-white font-medium">{t.label}</span>
              <span className="text-gray-500"> ({maskText(`${t.percentOfTotal.toFixed(0)}%`)})</span>
              <div className="text-gray-600">{t.trigger}</div>
            </div>
            <span className="text-gray-300 font-mono shrink-0">{maskText(fmtINR(t.amount))}</span>
          </div>
        ))}
      </div>
      {plan.suitableFunds.length > 0 && (
        <div className="text-2xs text-gray-600 mt-2">Suitable funds already in your portfolio: {plan.suitableFunds.join(', ')}</div>
      )}
      <p className="text-2xs text-gray-700 mt-1.5">{plan.basis}</p>
    </div>
  );
}

function RedemptionCard({ r, onReinvested }: { r: MfRedemption; onReinvested: () => void }) {
  const maskText = useMaskedText();
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');
  const [targetFund, setTargetFund] = useState('');
  const [saving, setSaving] = useState(false);

  const recordReinvestment = async () => {
    if (!amount) return;
    setSaving(true);
    try {
      await redemptionApi.recordReinvestment(r.id, { amount: Number(amount), targetFund: targetFund || undefined });
      setAmount(''); setTargetFund('');
      onReinvested();
    } catch {} finally { setSaving(false); }
  };

  return (
    <div className="border-b border-surface-border/40 last:border-0 py-2.5">
      <div className="flex items-center justify-between cursor-pointer" onClick={() => setOpen(o => !o)}>
        <div className="min-w-0">
          <div className="text-white text-xs font-medium truncate max-w-[220px]">{r.fundName ?? r.symbol}</div>
          <div className="text-2xs text-gray-600">
            Redeemed {r.redemptionDate} · {r.gainType} · {maskText(fmtINR(r.redeemedAmount))}
            {r.status === 'ACTIVE' && <span className="text-yellow-400"> · {maskText(fmtINR(r.cashRemaining))} left to reinvest</span>}
            {r.status === 'COMPLETED' && <span className="text-bull"> · fully reinvested</span>}
          </div>
        </div>
        {open ? <ChevronUp size={13} className="text-gray-500 shrink-0" /> : <ChevronDown size={13} className="text-gray-500 shrink-0" />}
      </div>
      {open && (
        <div className="mt-2 space-y-2">
          <div className="grid grid-cols-2 gap-2 text-2xs">
            <div className="bg-surface-hover rounded p-2"><div className="stat-label text-2xs">Capital Gain</div><div className={`font-mono font-semibold ${r.capitalGain >= 0 ? 'text-bull' : 'text-bear'}`}>{maskText(fmtINR(r.capitalGain))}</div></div>
            <div className="bg-surface-hover rounded p-2"><div className="stat-label text-2xs">Estimated Tax</div><div className="font-mono font-semibold text-white">{maskText(fmtINR(r.estimatedTax))}</div></div>
          </div>
          {r.reinvestments.length > 0 && (
            <div className="space-y-1">
              <div className="stat-label text-2xs">Reinvestment history</div>
              {r.reinvestments.map(re => (
                <div key={re.id} className="flex justify-between text-2xs text-gray-400">
                  <span>{re.date}{re.targetFund ? ` · ${re.targetFund}` : ''}</span>
                  <span className="font-mono text-white">{maskText(fmtINR(re.amount))}</span>
                </div>
              ))}
            </div>
          )}
          {r.status === 'ACTIVE' && <DeploymentPlanCard redemptionId={r.id} />}
          {r.status === 'ACTIVE' && (
            <div className="flex items-center gap-1.5 flex-wrap">
              <input type="number" value={amount} onChange={e => setAmount(e.target.value)} placeholder="Amount" className="input-field text-xs w-24 py-1" />
              <input value={targetFund} onChange={e => setTargetFund(e.target.value)} placeholder="Fund (optional)" className="input-field text-xs w-32 py-1" />
              <button onClick={recordReinvestment} disabled={saving || !amount} className="btn-primary text-xs py-1 px-3">
                {saving ? 'Saving…' : 'Record reinvestment'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// Tracks mutual funds after they've been redeemed — capital gains/tax, and stays active
// until the full amount has been reinvested, with a staged deployment plan.
export function RedeemedInvestments() {
  const [redemptions, setRedemptions] = useState<MfRedemption[] | null>(null);

  const load = useCallback(() => {
    redemptionApi.list().then(r => setRedemptions(r.data)).catch(() => setRedemptions([]));
  }, []);

  useEffect(() => { load(); }, [load]);

  if (!redemptions || redemptions.length === 0) return null;

  return (
    <div className="card">
      <div className="flex items-center gap-2 mb-1">
        <Landmark size={14} className="text-brand" />
        <h3 className="font-semibold text-white text-sm">Redeemed Investments</h3>
        <span className="text-2xs bg-brand/15 text-brand px-1.5 py-0.5 rounded-full">{redemptions.length}</span>
      </div>
      <div>
        {redemptions.map(r => <RedemptionCard key={r.id} r={r} onReinvested={load} />)}
      </div>
    </div>
  );
}
