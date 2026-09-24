import { useState } from 'react';
import { Wallet, TrendingUp, Landmark, PartyPopper, ArrowRight, X, Loader } from 'lucide-react';
import { ledgerApi } from '../../api/ledger';
import { portfolioApi } from '../../api/portfolio';
import { trackingApi } from '../../api/tracking';

type Step = 'welcome' | 'cash' | 'holding' | 'fd' | 'done';

const STEP_ORDER: Step[] = ['welcome', 'cash', 'holding', 'fd', 'done'];

interface Props {
  userName: string;
  onFinish: () => void;
}

/**
 * Shown once, right after a brand-new account is created — the dashboard is otherwise
 * completely empty, which reads as broken rather than "new". Each step is optional
 * (Skip always moves on); nothing here is required to reach the dashboard.
 *
 * Reuses the exact same APIs the wealth-tab "Add" forms use (ledgerApi/portfolioApi/
 * trackingApi) — this is not a separate onboarding-only code path, just a friendlier front
 * door onto the same add flows.
 */
export function OnboardingModal({ userName, onFinish }: Props) {
  const [step, setStep] = useState<Step>('welcome');
  const [added, setAdded] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const stepIndex = STEP_ORDER.indexOf(step);
  const goNext = () => setStep(STEP_ORDER[Math.min(stepIndex + 1, STEP_ORDER.length - 1)]);

  const [bank, setBank] = useState('');
  const [balance, setBalance] = useState('');
  const submitCash = async () => {
    if (!bank.trim() || !balance) { goNext(); return; }
    setSaving(true); setError('');
    try {
      await ledgerApi.addAccount({ name: bank.trim(), bank: bank.trim(), accountType: 'SAVINGS', balance: Number(balance) });
      setAdded(a => [...a, `${bank.trim()} — ₹${Number(balance).toLocaleString('en-IN')}`]);
      goNext();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save that account — you can add it later from My Wealth.');
    } finally {
      setSaving(false);
    }
  };

  const [symbol, setSymbol] = useState('');
  const [qty, setQty] = useState('');
  const [avgCost, setAvgCost] = useState('');
  const submitHolding = async () => {
    if (!symbol.trim() || !qty || !avgCost) { goNext(); return; }
    setSaving(true); setError('');
    try {
      const { data: portfolios } = await portfolioApi.list();
      const portfolioId = portfolios.length > 0
        ? portfolios[0].id
        : (await portfolioApi.create('My Portfolio')).data.id;
      const sym = symbol.trim().toUpperCase();
      await portfolioApi.addHolding(portfolioId, {
        symbol: sym.endsWith('.NS') ? sym : `${sym}.NS`,
        name: sym.replace('.NS', ''),
        quantity: Number(qty),
        price: Number(avgCost),
        transactionDate: new Date().toISOString().slice(0, 10),
      });
      setAdded(a => [...a, `${sym.replace('.NS', '')} × ${qty}`]);
      goNext();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save that holding — you can add it later from Investments.');
    } finally {
      setSaving(false);
    }
  };

  const [fdBank, setFdBank] = useState('');
  const [fdPrincipal, setFdPrincipal] = useState('');
  const [fdRate, setFdRate] = useState('');
  const submitFd = async () => {
    if (!fdBank.trim() || !fdPrincipal || !fdRate) { goNext(); return; }
    setSaving(true); setError('');
    try {
      await trackingApi.addFd({
        bank: fdBank.trim(), principal: Number(fdPrincipal), rate: Number(fdRate),
        compounding: 'quarterly', autoRenew: false,
      });
      setAdded(a => [...a, `${fdBank.trim()} FD — ₹${Number(fdPrincipal).toLocaleString('en-IN')}`]);
      goNext();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save that FD — you can add it later from My Wealth.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="relative bg-surface rounded-2xl border border-surface-border shadow-2xl w-full max-w-lg overflow-hidden">
        {/* Progress */}
        {step !== 'welcome' && step !== 'done' && (
          <div className="flex gap-1 p-4 pb-0">
            {STEP_ORDER.slice(1, -1).map((s, i) => (
              <div key={s} className={`h-1 flex-1 rounded-full ${i <= stepIndex - 1 ? 'bg-brand' : 'bg-surface-hover'}`} />
            ))}
          </div>
        )}

        <div className="p-6">
          {step === 'welcome' && (
            <div className="text-center">
              <div className="icon-badge-brand w-14 h-14 mx-auto mb-4"><Wallet size={24} /></div>
              <h2 className="text-xl font-bold text-ink mb-2">Welcome, {userName.split(' ')[0]}</h2>
              <p className="text-sm text-gray-500 mb-6">
                Your account is ready. Add what you already hold — bank balance, a stock or fund,
                a fixed deposit — and your dashboard reflects your real net worth from the first
                screen instead of starting from zero. Every step below is optional.
              </p>
              <button onClick={goNext} className="btn-primary w-full py-2.5">
                Set up my wealth profile <ArrowRight size={15} />
              </button>
              <button onClick={onFinish} className="btn-ghost w-full mt-2 justify-center text-sm">
                Skip — I'll add this later
              </button>
            </div>
          )}

          {step === 'cash' && (
            <div>
              <div className="icon-badge-accent w-11 h-11 mb-3"><Landmark size={18} /></div>
              <h2 className="text-lg font-semibold text-ink mb-1">Add a bank account</h2>
              <p className="text-sm text-gray-500 mb-4">Your current balance, so cash counts toward net worth.</p>
              {error && <p className="text-xs text-bear mb-3">{error}</p>}
              <div className="space-y-3">
                <input value={bank} onChange={e => setBank(e.target.value)} placeholder="Bank name (e.g. HDFC Bank)"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
                <input value={balance} onChange={e => setBalance(e.target.value)} type="number" inputMode="decimal" placeholder="Current balance (₹)"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
              </div>
              <StepFooter onSkip={goNext} onNext={submitCash} saving={saving} nextLabel={bank.trim() && balance ? 'Save & continue' : 'Continue'} />
            </div>
          )}

          {step === 'holding' && (
            <div>
              <div className="icon-badge-bull w-11 h-11 mb-3"><TrendingUp size={18} /></div>
              <h2 className="text-lg font-semibold text-ink mb-1">Add a stock or fund</h2>
              <p className="text-sm text-gray-500 mb-4">One holding is enough to get started — add the rest anytime from Investments, or use Bulk Import for many at once.</p>
              {error && <p className="text-xs text-bear mb-3">{error}</p>}
              <div className="grid grid-cols-2 gap-3">
                <input value={symbol} onChange={e => setSymbol(e.target.value)} placeholder="Symbol (e.g. RELIANCE)"
                  className="col-span-2 w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
                <input value={qty} onChange={e => setQty(e.target.value)} type="number" placeholder="Quantity"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
                <input value={avgCost} onChange={e => setAvgCost(e.target.value)} type="number" placeholder="Avg cost (₹)"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
              </div>
              <StepFooter onSkip={goNext} onNext={submitHolding} saving={saving} nextLabel={symbol.trim() && qty && avgCost ? 'Save & continue' : 'Continue'} />
            </div>
          )}

          {step === 'fd' && (
            <div>
              <div className="icon-badge-gold w-11 h-11 mb-3"><Landmark size={18} /></div>
              <h2 className="text-lg font-semibold text-ink mb-1">Add a fixed deposit</h2>
              <p className="text-sm text-gray-500 mb-4">We'll track maturity and renewal automatically.</p>
              {error && <p className="text-xs text-bear mb-3">{error}</p>}
              <div className="grid grid-cols-2 gap-3">
                <input value={fdBank} onChange={e => setFdBank(e.target.value)} placeholder="Bank name"
                  className="col-span-2 w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
                <input value={fdPrincipal} onChange={e => setFdPrincipal(e.target.value)} type="number" placeholder="Principal (₹)"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
                <input value={fdRate} onChange={e => setFdRate(e.target.value)} type="number" step="0.01" placeholder="Rate (% p.a.)"
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50" />
              </div>
              <StepFooter onSkip={goNext} onNext={submitFd} saving={saving} nextLabel={fdBank.trim() && fdPrincipal && fdRate ? 'Save & finish' : 'Finish'} />
            </div>
          )}

          {step === 'done' && (
            <div className="text-center">
              <div className="icon-badge-bull w-14 h-14 mx-auto mb-4"><PartyPopper size={22} /></div>
              <h2 className="text-xl font-bold text-ink mb-2">You're all set</h2>
              {added.length > 0 ? (
                <div className="text-left bg-surface-hover rounded-lg p-3 mb-5 space-y-1">
                  {added.map((a, i) => <div key={i} className="text-sm text-gray-300">✓ {a}</div>)}
                </div>
              ) : (
                <p className="text-sm text-gray-500 mb-5">
                  Nothing added yet — that's fine, you can add everything later from My Wealth,
                  Investments, or connect your email for automatic statement import.
                </p>
              )}
              <button onClick={onFinish} className="btn-primary w-full py-2.5">Go to Dashboard</button>
            </div>
          )}
        </div>

        {step !== 'welcome' && step !== 'done' && (
          <button aria-label="Close" onClick={onFinish} className="absolute top-4 right-4 btn-icon"><X size={16} /></button>
        )}
      </div>
    </div>
  );
}

function StepFooter({ onSkip, onNext, saving, nextLabel }: { onSkip: () => void; onNext: () => void; saving: boolean; nextLabel: string }) {
  return (
    <div className="flex items-center gap-3 mt-5">
      <button onClick={onSkip} disabled={saving} className="btn-ghost text-sm flex-1 justify-center disabled:opacity-50">Skip</button>
      <button onClick={onNext} disabled={saving} className="btn-primary text-sm flex-1 disabled:opacity-50">
        {saving ? <><Loader size={14} className="animate-spin" /> Saving…</> : nextLabel}
      </button>
    </div>
  );
}
